import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AgreementHttpError,
  claimAgreement,
  finaliseAgreement,
  getAgreement,
  listMyAgreements,
  updateAgreement,
  updateAgreementContacts,
} from "./agreements";
import { hasProblemType, PROBLEM } from "./problems";
import type { CreateAgreementInput } from "./client";

// The authenticated agreement calls ride the HttpOnly session cookie (same-origin credentials, never
// an Authorization header) and send the CSRF header on unsafe verbs. The CSRF cookie is mocked so no
// bootstrap GET enters the call sequence; a non-2xx surfaces an AgreementHttpError with the status.
vi.mock("./cookies", () => ({
  readCookie: (name: string) =>
    name === "__Host-XSRF-TOKEN" ? "csrf-token" : null,
}));

function okJson(body: unknown) {
  return { ok: true, status: 200, json: () => Promise.resolve(body) };
}
function status(code: number) {
  return { ok: false, status: code, json: () => Promise.resolve({}) };
}

const editInput: CreateAgreementInput = {
  propertyAddress: "1 Road",
  monthlyRent: "1000",
  securityDeposit: "2000",
  startDate: "2027-01-01",
  endDate: "2028-01-01",
  signers: [],
};

describe("agreements api", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("lists mine with the session cookie and no Authorization header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson([{ id: "a1" }]));
    vi.stubGlobal("fetch", fetchMock);

    const rows = await listMyAgreements();

    expect(rows).toEqual([{ id: "a1" }]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements");
    expect(init.credentials).toBe("same-origin");
    expect(JSON.stringify(init.headers ?? {})).not.toContain("Authorization");
  });

  it("claims (saves) with a POST to /{id}/claim and the CSRF header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await claimAgreement("a1");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements/a1/claim");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("same-origin");
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "csrf-token" });
  });

  it("edits with a PUT carrying the body and the CSRF header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await updateAgreement("a1", editInput);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements/a1");
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": "csrf-token",
    });
    expect(JSON.parse(init.body).propertyAddress).toBe("1 Road");
  });

  it("reads one for edit with a GET over the session cookie", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await getAgreement("a1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/agreements/a1",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("surfaces the HTTP status on failure (e.g. 409 frozen, 404 non-owner)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(409)));
    await expect(updateAgreement("a1", editInput)).rejects.toBeInstanceOf(
      AgreementHttpError,
    );
    await expect(updateAgreement("a1", editInput)).rejects.toHaveProperty(
      "status",
      409,
    );
  });
  // 409 is not one situation on the contacts route. The terms freeze and the contacts freeze are
  // different lines at different moments, and a client that cannot tell them apart ends up telling
  // the customer to retry something that refuses forever.
  it("carries the RFC 9457 problem type when a contacts save is refused", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: () =>
          Promise.resolve({
            type: "urn:agreementmitra:problem:contacts-frozen",
          }),
      }),
    );

    const failure = await updateAgreementContacts("a1", [
      { signerId: "s1", email: "a@b.com", mobile: "" },
    ]).catch((e) => e);

    expect(failure).toBeInstanceOf(AgreementHttpError);
    expect(failure.status).toBe(409);
    expect(failure.problemType).toBe(
      "urn:agreementmitra:problem:contacts-frozen",
    );
    expect(hasProblemType(failure, PROBLEM.contactsFrozen)).toBe(true);
  });

  it("does not mistake the terms freeze for the contacts freeze", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: () =>
          Promise.resolve({ type: "urn:agreementmitra:problem:draft-frozen" }),
      }),
    );

    const failure = await updateAgreementContacts("a1", []).catch((e) => e);

    expect(hasProblemType(failure, PROBLEM.contactsFrozen)).toBe(false);
  });

  it("degrades to a null problem type when the body is not problem+json", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: () => Promise.reject(new Error("not json")),
      }),
    );

    const failure = await updateAgreementContacts("a1", []).catch((e) => e);

    expect(failure.problemType).toBeNull();
    expect(hasProblemType(failure, PROBLEM.contactsFrozen)).toBe(false);
  });
});

// Every call carries the problem type, not only the one that once opted in (D2). Literal server URNs.
describe("agreements api: every refusal carries its problem type", () => {
  afterEach(() => vi.unstubAllGlobals());

  const calls: [string, () => Promise<unknown>][] = [
    ["listMyAgreements", () => listMyAgreements()],
    ["claimAgreement", () => claimAgreement("a1")],
    ["getAgreement", () => getAgreement("a1")],
    ["finaliseAgreement", () => finaliseAgreement("a1")],
    ["updateAgreement", () => updateAgreement("a1", editInput)],
    ["updateAgreementContacts", () => updateAgreementContacts("a1", [])],
  ];

  it.each(calls)(
    "%s keeps the status and exact type of a 409",
    async (_, call) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockImplementation(() =>
          Promise.resolve(
            new Response(
              JSON.stringify({
                type: "urn:agreementmitra:problem:jurisdiction-unsupported",
              }),
              {
                status: 409,
                headers: { "Content-Type": "application/problem+json" },
              },
            ),
          ),
        ),
      );

      const failure = await call().catch((e) => e);

      expect(failure).toBeInstanceOf(AgreementHttpError);
      expect(failure.status).toBe(409);
      expect(failure.problemType).toBe(
        "urn:agreementmitra:problem:jurisdiction-unsupported",
      );
    },
  );

  it("degrades to a null type for a non-JSON body", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("<html>bad gateway</html>", { status: 502 }),
        ),
    );

    const failure = await finaliseAgreement("a1").catch((e) => e);

    expect(failure).toBeInstanceOf(AgreementHttpError);
    expect(failure.status).toBe(502);
    expect(failure.problemType).toBeNull();
  });
});

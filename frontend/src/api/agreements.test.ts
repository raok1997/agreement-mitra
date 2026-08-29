import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AgreementHttpError,
  claimAgreement,
  getAgreement,
  listMyAgreements,
  updateAgreement,
  updateAgreementContacts,
} from "./agreements";
import type { CreateAgreementInput } from "./client";

// The authenticated agreement calls must attach the Bearer header (from the auth store). We stub the
// store so a session is always present, then assert each verb hits the right URL/method with the
// header -- and that a non-2xx surfaces an AgreementHttpError carrying the status.
vi.mock("./authStore", () => ({
  authHeader: () => ({ Authorization: "Bearer test-session" }),
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

  it("lists mine with the Bearer header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson([{ id: "a1" }]));
    vi.stubGlobal("fetch", fetchMock);

    const rows = await listMyAgreements();

    expect(rows).toEqual([{ id: "a1" }]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/agreements",
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: "Bearer test-session",
        }),
      }),
    );
  });

  it("claims (saves) with a POST to /{id}/claim and the Bearer header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await claimAgreement("a1");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements/a1/claim");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      Authorization: "Bearer test-session",
    });
  });

  it("edits with a PUT carrying the body and the Bearer header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await updateAgreement("a1", editInput);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements/a1");
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({
      Authorization: "Bearer test-session",
    });
    expect(JSON.parse(init.body).propertyAddress).toBe("1 Road");
  });

  it("reads one for edit with a GET and the Bearer header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: "a1" }));
    vi.stubGlobal("fetch", fetchMock);

    await getAgreement("a1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/agreements/a1",
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: "Bearer test-session",
        }),
      }),
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
    expect(failure.contactsFrozen).toBe(true);
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

    expect(failure.contactsFrozen).toBe(false);
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
    expect(failure.contactsFrozen).toBe(false);
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { requestSignature, type SignSession } from "./client";

// A CSRF cookie is present, so unsafe calls insert no bootstrap GET into the fetch sequence.
vi.mock("./cookies", () => ({
  readCookie: (name: string) =>
    name === "__Host-XSRF-TOKEN" ? "csrf-token" : null,
}));

describe("requestSignature", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("POSTs to the signing request endpoint for the given agreement", async () => {
    const session: SignSession = {
      providerRequestId: "req-1",
      signingUrl: "https://sandbox.example/sign/abc",
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(session),
    });
    vi.stubGlobal("fetch", fetchMock);

    await requestSignature("agr-42");

    expect(fetchMock).toHaveBeenCalledWith("/api/signing/agr-42/request", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-XSRF-TOKEN": "csrf-token" },
    });
  });

  it("resolves to the parsed SignSession on an ok response", async () => {
    const session: SignSession = {
      providerRequestId: "req-1",
      signingUrl: "https://sandbox.example/sign/abc",
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue({ ok: true, json: () => Promise.resolve(session) }),
    );

    await expect(requestSignature("agr-42")).resolves.toEqual(session);
  });

  it("rejects when the response is not ok", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: () => Promise.resolve({}),
      }),
    );

    await expect(requestSignature("agr-42")).rejects.toThrow(/500/);
  });
});

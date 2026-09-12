import { afterEach, describe, expect, it, vi } from "vitest";
import {
  downloadSignedDocument,
  getSigningProgress,
  SigningProgressHttpError,
} from "./signingProgress";

// Both reads attach the Bearer header from the auth store: a claimed agreement answers only its
// owner, so a request that dropped the header would be refused for exactly the customer who
// saved it. The download in particular must be a fetch, never a bare link -- a link carries no
// header -- and must never put the session in the URL.
vi.mock("./authStore", () => ({
  authHeader: () => ({ Authorization: "Bearer test-session" }),
}));

function okJson(body: unknown) {
  return { ok: true, status: 200, json: () => Promise.resolve(body) };
}
function status(code: number) {
  return { ok: false, status: code, json: () => Promise.resolve({}) };
}

describe("signingProgress api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("reads progress with the Bearer header", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        okJson({ agreementId: "a1", stage: "AWAITING_STAMP" }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const progress = await getSigningProgress("a1");

    expect(progress.stage).toBe("AWAITING_STAMP");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/signing/a1/progress",
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: "Bearer test-session",
        }),
      }),
    );
  });

  it("surfaces a non-2xx as a SigningProgressHttpError carrying the status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(404)));

    await expect(getSigningProgress("a1")).rejects.toMatchObject({
      name: "SigningProgressHttpError",
      status: 404,
    });
    await expect(getSigningProgress("a1")).rejects.toBeInstanceOf(
      SigningProgressHttpError,
    );
  });

  it("downloads the signed document with the Bearer header, via a blob URL, never a token in the URL", async () => {
    vi.useFakeTimers();
    const blob = new Blob(["%PDF-1.7"], { type: "application/pdf" });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: () => Promise.resolve(blob),
    });
    vi.stubGlobal("fetch", fetchMock);
    const createObjectURL = vi.fn().mockReturnValue("blob:doc");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL,
      revokeObjectURL,
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});

    await downloadSignedDocument("a1", "AM1-signed.pdf");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agreements/a1/signed-document");
    expect(url).not.toContain("test-session");
    expect(init.headers).toMatchObject({
      Authorization: "Bearer test-session",
    });
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(click).toHaveBeenCalledOnce();
    // The download navigation is asynchronous: the URL outlives the click, then is revoked.
    expect(revokeObjectURL).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:doc");
  });

  it("does not hand the browser anything when the download is refused", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(404)));
    const createObjectURL = vi.fn();
    vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL: vi.fn() });

    await expect(downloadSignedDocument("a1")).rejects.toMatchObject({
      status: 404,
    });
    expect(createObjectURL).not.toHaveBeenCalled();
  });
});

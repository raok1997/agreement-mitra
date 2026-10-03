import { afterEach, describe, expect, it, vi } from "vitest";
import {
  downloadSignedDocument,
  getSigningProgress,
  SigningProgressHttpError,
} from "./signingProgress";

// Both reads ride the HttpOnly session cookie (same-origin credentials): a claimed agreement answers
// only its owner. The download stays a fetch turned into a blob URL, and never puts anything
// session-like in the URL.
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

describe("signingProgress api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("reads progress over the session cookie", async () => {
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
      expect.objectContaining({ credentials: "same-origin" }),
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

  it("downloads the signed document over the session cookie, via a blob URL, never a token in the URL", async () => {
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
    expect(url).not.toContain("session");
    expect(init.credentials).toBe("same-origin");
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

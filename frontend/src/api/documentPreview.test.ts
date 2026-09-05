import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fetchDocumentPreviewHtml,
  fetchDocumentPreviewPdf,
} from "./documentPreview";

const URL = "/api/templates/document/preview";

describe("document-preview api client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("POSTs the flat data map + dimensions with Accept: text/html and returns the HTML", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve("<p>hi</p>"),
    });
    vi.stubGlobal("fetch", fetchMock);

    const html = await fetchDocumentPreviewHtml(
      { tenantName: "Tara Sen", monthlyRent: "25000" },
      { state: "TG", type: "residential" },
      ["Pets"],
    );

    expect(html).toBe("<p>hi</p>");
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(URL);
    expect(init.method).toBe("POST");
    expect(init.headers.Accept).toBe("text/html");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(init.body)).toEqual({
      dimensions: { state: "TG", type: "residential" },
      data: { tenantName: "Tara Sen", monthlyRent: "25000" },
      activeSections: ["Pets"],
    });
  });

  it("sends activeSections as [] when none are given (both faces request identical content)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, text: () => Promise.resolve("") });
    vi.stubGlobal("fetch", fetchMock);

    await fetchDocumentPreviewHtml(
      { tenantName: "Tara Sen" },
      { state: "TG", type: "residential" },
    );

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.activeSections).toEqual([]);
  });

  it("POSTs with Accept: application/pdf and returns the blob", async () => {
    const blob = new Blob(["%PDF-"], { type: "application/pdf" });
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) });
    vi.stubGlobal("fetch", fetchMock);

    const out = await fetchDocumentPreviewPdf(
      { ownerName: "Asha Rao" },
      { state: "TG", type: "residential" },
      ["Pets"],
    );

    expect(out).toBe(blob);
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers.Accept).toBe("application/pdf");
    expect(JSON.parse(init.body).data).toEqual({ ownerName: "Asha Rao" });
    // The PDF face carries the SAME activeSections as the HTML face (parity, D3).
    expect(JSON.parse(init.body).activeSections).toEqual(["Pets"]);
  });

  it("omits dimensions from the body when none are given", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, text: () => Promise.resolve("") });
    vi.stubGlobal("fetch", fetchMock);

    await fetchDocumentPreviewHtml({ tenantName: "Tara Sen" });

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).toEqual({
      data: { tenantName: "Tara Sen" },
      activeSections: [],
    });
    expect("dimensions" in body).toBe(false);
  });

  it("rejects on a non-OK response with only the status, logging nothing sensitive", async () => {
    const logSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    const errSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        text: () => Promise.resolve(""),
      }),
    );

    await expect(
      fetchDocumentPreviewHtml(
        { tenantName: "Tara Sen" },
        { state: "TG", type: "residential" },
      ),
    ).rejects.toThrow(/422/);

    const logged = [...logSpy.mock.calls, ...errSpy.mock.calls]
      .flat()
      .join(" ");
    expect(logged).not.toContain("Tara");
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getTemplate,
  listTemplates,
  type TemplateDetail,
  type TemplateSummary,
} from "./templateCatalog";

describe("template-catalog api client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GETs /api/templates with no query when no filters are given", async () => {
    const rows: TemplateSummary[] = [];
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: () => Promise.resolve(rows) });
    vi.stubGlobal("fetch", fetchMock);

    await listTemplates();

    expect(fetchMock).toHaveBeenCalledWith("/api/templates");
  });

  it("passes state, type, and q as query params", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    vi.stubGlobal("fetch", fetchMock);

    await listTemplates({ state: "TG", type: "residential", q: "rent" });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/templates?state=TG&type=residential&q=rent",
    );
  });

  it("GETs one template by id and returns its detail", async () => {
    const detail: TemplateDetail = {
      id: "abc",
      name: "Residential Rental",
      description: "blurb",
      dimensions: { state: "TG", type: "residential", language: "en" },
      version: 1,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: () => Promise.resolve(detail) });
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTemplate("abc")).resolves.toEqual(detail);
    expect(fetchMock).toHaveBeenCalledWith("/api/templates/abc");
  });

  it("rejects on a 404 (unknown or non-published id)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 404, json: () => Promise.resolve({}) }),
    );

    await expect(getTemplate("missing")).rejects.toThrow(/404/);
  });
});

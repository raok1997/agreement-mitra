import { afterEach, describe, expect, it, vi } from "vitest";
import { getTemplateForm, type FormSchema, type FormSection } from "./templateForm";

function sampleSchema(): FormSchema {
  return {
    dimensions: { state: "TG", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "abc123",
    sections: [{ title: "Parties", fields: [], optional: false, renderKind: "keyvalue" }],
  };
}

describe("template-form api client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GETs /api/templates/form with state and type query params", async () => {
    const schema = sampleSchema();
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(schema) });
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTemplateForm("TG", "residential")).resolves.toEqual(schema);
    expect(fetchMock).toHaveBeenCalledWith("/api/templates/form?state=TG&type=residential");
  });

  it("mirrors the backend FormSection: optional + renderKind typecheck and round-trip", async () => {
    // A FormSection carrying the two new fields must satisfy the mirror type and survive a
    // JSON round-trip unchanged (the fields are additive to the wire body). Full rail/catalog
    // behaviour that consumes these fields is M4 (capture-mandatory-optional-ux), not this CR.
    const mandatory: FormSection = {
      title: "Parties",
      fields: [],
      optional: false,
      renderKind: "parties",
    };
    const optionalAnnexure: FormSection = {
      title: "Add-ons",
      fields: [],
      optional: true,
      renderKind: "annexure",
    };

    const roundTripped = JSON.parse(JSON.stringify([mandatory, optionalAnnexure])) as FormSection[];
    expect(roundTripped).toEqual([mandatory, optionalAnnexure]);
    expect(roundTripped[0].optional).toBe(false);
    expect(roundTripped[0].renderKind).toBe("parties");
    expect(roundTripped[1].optional).toBe(true);
    expect(roundTripped[1].renderKind).toBe("annexure");
  });

  it("rejects on a 404 (unknown state/type) without leaking the requested dimensions", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: false, status: 404, json: () => Promise.resolve({}) });
    vi.stubGlobal("fetch", fetchMock);

    // The message must carry only the status, never the probed state/type.
    await expect(getTemplateForm("ZZ", "secret-type")).rejects.toThrow(/404/);
    await expect(getTemplateForm("ZZ", "secret-type")).rejects.not.toThrow(/ZZ|secret-type/);
  });
});

import { describe, it, expect } from "vitest";
import type { FormField, FormSchema } from "../api/templateForm";
import {
  defaultString,
  emptyWorking,
  fieldErrors,
  isSectionComplete,
  isSectionMandatory,
  isSectionRequired,
  reconcileActiveSections,
  sectionIcon,
  sectionId,
  validateField,
} from "./formModel";

function field(over: Partial<FormField> = {}): FormField {
  return {
    key: "k",
    label: "K",
    widget: "text",
    type: "text",
    required: false,
    ...over,
  };
}

function schemaWith(
  sections: { title: string; optional?: boolean }[],
): FormSchema {
  return {
    dimensions: { state: "IN", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "h",
    sections: sections.map((s) => ({
      title: s.title,
      fields: [field()],
      optional: s.optional ?? false,
      renderKind: "keyvalue",
    })),
  };
}

describe("formModel: registry building", () => {
  it("slugifies section titles into stable ids", () => {
    expect(sectionId("Financial terms", 1)).toBe("financial-terms");
    expect(sectionId("!!!", 3)).toBe("section-3");
  });

  it("derives a two-letter icon from a title", () => {
    expect(sectionIcon("Financial terms")).toBe("FT");
    expect(sectionIcon("Parties")).toBe("PA");
  });

  it("coerces declared defaults to strings (booleans -> true/empty)", () => {
    expect(
      defaultString(field({ default: 6, type: "int", widget: "number" })),
    ).toBe("6");
    expect(
      defaultString(field({ default: true, type: "bool", widget: "checkbox" })),
    ).toBe("true");
    expect(
      defaultString(
        field({ default: false, type: "bool", widget: "checkbox" }),
      ),
    ).toBe("");
    expect(defaultString(field())).toBe("");
  });

  it("builds an empty, default-seeded working set from a schema", () => {
    const schema: FormSchema = {
      dimensions: { state: "TG", type: "residential" },
      templateId: "rental-base",
      version: 1,
      contentHash: "h",
      sections: [
        { title: "Parties", fields: [field({ key: "ownerName" })] },
        {
          title: "Term",
          fields: [
            field({
              key: "furnished",
              type: "bool",
              widget: "checkbox",
              default: true,
            }),
          ],
        },
      ],
    };
    expect(emptyWorking(schema)).toEqual({
      parties: { ownerName: "" },
      term: { furnished: "true" },
    });
    expect(emptyWorking(null)).toEqual({});
  });
});

describe("formModel: client validation", () => {
  it("flags a required field only when empty", () => {
    expect(validateField(field({ required: true }), "")).toMatch(/required/);
    expect(validateField(field({ required: true }), "x")).toBeNull();
    expect(validateField(field({ required: false }), "")).toBeNull();
  });

  it("enforces numeric min/max and integer-ness", () => {
    const f = field({
      type: "int",
      widget: "number",
      validation: { min: 1, max: 60 },
    });
    expect(validateField(f, "0")).toMatch(/at least 1/);
    expect(validateField(f, "61")).toMatch(/at most 60/);
    expect(validateField(f, "12")).toBeNull();
    expect(validateField(f, "1.5")).toMatch(/whole number/);
    expect(validateField(f, "abc")).toMatch(/must be a number/);
  });

  it("enforces money min without requiring integer-ness", () => {
    const f = field({ type: "money", widget: "money", validation: { min: 0 } });
    expect(validateField(f, "1500.50")).toBeNull();
    expect(validateField(f, "-1")).toMatch(/at least 0/);
  });

  it("enforces text length and pattern", () => {
    const f = field({
      validation: { minLength: 2, maxLength: 4, pattern: "^[a-z]+$" },
    });
    expect(validateField(f, "a")).toMatch(/at least 2/);
    expect(validateField(f, "abcde")).toMatch(/at most 4/);
    expect(validateField(f, "AB")).toMatch(/expected format/);
    expect(validateField(f, "abc")).toBeNull();
  });

  it("restricts enum input to the declared options", () => {
    const f = field({
      type: "enum",
      widget: "select",
      options: [
        { value: "owner", label: "Owner" },
        { value: "tenant", label: "Tenant" },
      ],
    });
    expect(validateField(f, "landlord")).toMatch(/valid/);
    expect(validateField(f, "owner")).toBeNull();
  });

  it("derives section completeness from required fields only", () => {
    const fields = [
      field({ key: "a", required: true }),
      field({ key: "b", required: false }),
    ];
    expect(isSectionRequired(fields)).toBe(true);
    expect(isSectionComplete(fields, { a: "", b: "" })).toBe(false);
    expect(isSectionComplete(fields, { a: "x", b: "" })).toBe(true);
    expect(fieldErrors(fields, { a: "", b: "" })).toEqual({
      a: expect.stringMatching(/required/),
    });
  });
});

describe("formModel: mandatory/optional section semantics", () => {
  it("reads mandatory-ness from FormSection.optional, defaulting a missing flag to mandatory", () => {
    // Explicit flag wins...
    expect(isSectionMandatory({ optional: false })).toBe(true);
    expect(isSectionMandatory({ optional: true })).toBe(false);
    // ...and a schema that predates M3 (no `optional`) is treated as mandatory, matching the frozen
    // default, so the shell behaves as today.
    expect(isSectionMandatory({})).toBe(true);
    // It is decoupled from field-level required-ness: an optional section with a required field is still
    // optional, and a mandatory section with only optional fields is still mandatory.
    expect(isSectionMandatory({ optional: true })).toBe(false);
  });

  it("reconciles a stored activeSections set against the schema, dropping stale titles in order", () => {
    const schema = schemaWith([
      { title: "Parties" }, // mandatory
      { title: "Pets", optional: true },
      { title: "Parking", optional: true },
    ]);
    // Keeps only titles that are still an OPTIONAL section, preserving the stored order; drops a
    // now-mandatory title ("Parties"), a renamed/removed title ("Garden"), and unknown titles.
    expect(
      reconcileActiveSections(["Parking", "Garden", "Pets", "Parties"], schema),
    ).toEqual(["Parking", "Pets"]);
    // A null schema (not yet loaded) reconciles to an empty set.
    expect(reconcileActiveSections(["Pets"], null)).toEqual([]);
  });
});

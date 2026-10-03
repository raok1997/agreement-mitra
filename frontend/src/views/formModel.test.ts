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
  REGISTRABLE_OVER_MONTHS,
  requiresRegistration,
  sectionErrors,
  sectionIcon,
  sectionId,
  tenancyMonths,
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

// ---------------------------------------------------------------------------------------------
// Derived tenancy term, cross-field validation, and the registrability threshold.
// ---------------------------------------------------------------------------------------------

describe("tenancyMonths", () => {
  // KEEP IN SYNC with the backend table in
  // backend/src/test/java/in/agreementmitra/documents/template/DerivedFieldTest.java
  // (wholeMonthsBetweenDates). Both sides must agree case for case: the server's count is what the
  // document states and what is stored, so a divergence here would show a wrong number on screen.
  it.each([
    // The reported case: a two-year span against a form still showing 11.
    ["2026-01-08", "2028-01-08", 24],
    // Exactly eleven months -- the registrability line, and the common Indian tenancy.
    ["2026-01-01", "2026-12-01", 11],
    ["2026-01-01", "2027-01-01", 12],
    // A trailing partial month is truncated, never rounded up.
    ["2026-01-01", "2026-12-20", 11],
    ["2026-01-01", "2026-01-31", 0],
    // Month-end clamping: 31 Jan to 28 Feb is zero whole months, and must not overflow to March.
    ["2026-01-31", "2026-02-28", 0],
    ["2026-01-31", "2026-03-31", 2],
    // Leap years.
    ["2028-02-29", "2029-02-28", 11],
    ["2024-02-29", "2025-03-01", 12],
    // Same day is a zero-month term.
    ["2026-06-01", "2026-06-01", 0],
  ])("%s to %s is %i whole months", (start, end, expected) => {
    expect(tenancyMonths(start as string, end as string)).toBe(expected);
  });

  it("does not overflow the way Date month arithmetic would", () => {
    // new Date(2026, 0, 31) with a month added rolls over to 3 March, which would make this 1.
    expect(tenancyMonths("2026-01-31", "2026-02-28")).toBe(0);
    expect(tenancyMonths("2026-01-31", "2026-03-01")).toBe(1);
  });

  it("returns null when a date is missing, blank, or not a real calendar date", () => {
    expect(tenancyMonths("", "2026-12-01")).toBeNull();
    expect(tenancyMonths("2026-01-01", "")).toBeNull();
    expect(tenancyMonths("   ", "2026-12-01")).toBeNull();
    expect(tenancyMonths("not a date", "2026-12-01")).toBeNull();
    expect(tenancyMonths("01/01/2026", "2026-12-01")).toBeNull();
    // A day the month does not have is rejected rather than silently rolled over.
    expect(tenancyMonths("2026-02-31", "2026-12-01")).toBeNull();
    expect(tenancyMonths("2027-02-29", "2027-12-01")).toBeNull();
  });

  it("counts a backwards range as negative rather than throwing", () => {
    expect(tenancyMonths("2026-06-01", "2026-01-01")).toBe(-5);
  });
});

describe("requiresRegistration", () => {
  it("warns strictly ABOVE the eleven-month line, never at it", () => {
    expect(REGISTRABLE_OVER_MONTHS).toBe(11);
    expect(requiresRegistration(11)).toBe(false);
    expect(requiresRegistration(12)).toBe(true);
    expect(requiresRegistration(24)).toBe(true);
    expect(requiresRegistration(0)).toBe(false);
  });

  it("never warns on an undetermined term", () => {
    // A missing date must not produce a legal warning the user cannot act on.
    expect(requiresRegistration(null)).toBe(false);
  });
});

describe("sectionErrors", () => {
  const dateFields = [
    field({ key: "startDate", label: "Start", widget: "date", type: "date", required: true }),
    field({ key: "endDate", label: "End", widget: "date", type: "date", required: true }),
  ];

  it("reports an end date before the start date, against the END field", () => {
    const errors = sectionErrors(dateFields, {
      startDate: "2026-06-01",
      endDate: "2026-01-01",
    });
    expect(errors.endDate).toMatch(/after the start date/i);
    expect(errors.startDate).toBeUndefined();
  });

  it("reports an end date equal to the start date", () => {
    const errors = sectionErrors(dateFields, {
      startDate: "2026-06-01",
      endDate: "2026-06-01",
    });
    expect(errors.endDate).toMatch(/after the start date/i);
  });

  it("accepts a valid range", () => {
    expect(
      sectionErrors(dateFields, {
        startDate: "2026-01-08",
        endDate: "2028-01-08",
      }),
    ).toEqual({});
  });

  it("reports required rather than a confusing range error on a half-filled form", () => {
    const errors = sectionErrors(dateFields, {
      startDate: "2026-06-01",
      endDate: "",
    });
    expect(errors.endDate).toMatch(/required/i);
  });

  it("still reports every per-field error fieldErrors did", () => {
    const fields = [
      ...dateFields,
      field({ key: "rent", label: "Rent", widget: "money", type: "money", required: true }),
    ];
    const data = { startDate: "2026-06-01", endDate: "2026-01-01", rent: "" };
    // The cross-field layer ADDS to the per-field errors; it never replaces or masks them.
    const perField = fieldErrors(fields, data);
    const combined = sectionErrors(fields, data);
    for (const [key, message] of Object.entries(perField)) {
      expect(combined[key]).toBe(message);
    }
    expect(combined.endDate).toMatch(/after the start date/i);
  });

  it("leaves a section without both date fields alone", () => {
    const fields = [field({ key: "ownerName", label: "Owner", required: true })];
    expect(sectionErrors(fields, { ownerName: "Asha" })).toEqual({});
  });
});

describe("read-only (server-derived) fields", () => {
  const derived = field({
    key: "durationMonths",
    label: "Duration (months)",
    widget: "number",
    type: "int",
    required: true,
    readOnly: true,
  });

  it("never blocks section completeness, even if the schema still marks it required", () => {
    // Belt-and-braces: the backend projects readOnly fields required:false, but a stale cached
    // schema must not be able to strand a section as permanently incomplete.
    expect(isSectionComplete([derived], { durationMonths: "" })).toBe(true);
  });

  it("does not mask a genuinely incomplete required field alongside it", () => {
    const fields = [derived, field({ key: "ownerName", label: "Owner", required: true })];
    expect(isSectionComplete(fields, { durationMonths: "", ownerName: "" })).toBe(false);
    expect(isSectionComplete(fields, { durationMonths: "", ownerName: "Asha" })).toBe(true);
  });
});

// Pure, data-independent helpers that turn a fetched FormSchema into the shell's section registry
// and drive client-side validation from field metadata. No Vue, no I/O -- unit-testable in isolation.
// Client validation here is a UX affordance, NOT the trust boundary: authoritative validation of a
// submitted payload is server-side (document-projection CR).

import type { FormField, FormSchema } from "../api/templateForm";

/** In-progress values for one section, keyed by field key. Always strings (matches the shell). */
export type SectionData = Record<string, string>;

/** The full working set, keyed by section id. */
export type WorkingSet = Record<string, SectionData>;

/** Stable, slug-based id for a section, derived from its title (index-suffixed fallback). */
export function sectionId(title: string, index: number): string {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return slug || `section-${index}`;
}

/** A two-letter icon derived from a section title's initials (e.g. "Financial terms" -> "FT"). */
export function sectionIcon(title: string): string {
  const initials = title
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0])
    .join("");
  // Single-word titles give one initial -- fall back to the title's first two characters.
  const base = initials.length >= 2 ? initials : title;
  return base.slice(0, 2).toUpperCase();
}

/** A field's declared default as a string (booleans become "true"/""), else "". */
export function defaultString(field: FormField): string {
  if (field.default === undefined || field.default === null) return "";
  if (typeof field.default === "boolean") return field.default ? "true" : "";
  return String(field.default);
}

/** Build an empty (default-seeded) working set matching the schema's sections + fields. */
export function emptyWorking(schema: FormSchema | null): WorkingSet {
  const working: WorkingSet = {};
  if (!schema) return working;
  schema.sections.forEach((section, i) => {
    const id = sectionId(section.title, i);
    const data: SectionData = {};
    for (const field of section.fields) data[field.key] = defaultString(field);
    working[id] = data;
  });
  return working;
}

/**
 * Validate one field's raw string value against its metadata. Returns a human message when invalid,
 * or null when valid. Enforces required, numeric min/max (int/money), integer-ness (int), text
 * minLength/maxLength/pattern, and enum options membership.
 */
export function validateField(field: FormField, raw: string): string | null {
  const value = (raw ?? "").trim();

  if (field.widget === "checkbox") {
    if (field.required && value !== "true")
      return `${field.label} is required.`;
    return null;
  }

  if (!value) return field.required ? `${field.label} is required.` : null;

  const v = field.validation;
  if (field.type === "int" || field.type === "money") {
    const n = Number(value);
    if (!Number.isFinite(n)) return `${field.label} must be a number.`;
    if (field.type === "int" && !Number.isInteger(n))
      return `${field.label} must be a whole number.`;
    if (v?.min != null && n < v.min)
      return `${field.label} must be at least ${v.min}.`;
    if (v?.max != null && n > v.max)
      return `${field.label} must be at most ${v.max}.`;
  } else if (field.type === "text" || field.type === "longtext") {
    if (v?.minLength != null && value.length < v.minLength) {
      return `${field.label} must be at least ${v.minLength} characters.`;
    }
    if (v?.maxLength != null && value.length > v.maxLength) {
      return `${field.label} must be at most ${v.maxLength} characters.`;
    }
    if (v?.pattern) {
      try {
        if (!new RegExp(v.pattern).test(value))
          return `${field.label} is not in the expected format.`;
      } catch {
        // A malformed server-side pattern is not a client failure -- skip the check.
      }
    }
  } else if (field.type === "enum") {
    // Membership is checked on the option VALUE (the label is display-only).
    if (field.options && !field.options.some((o) => o.value === value))
      return `Choose a valid ${field.label}.`;
  }
  return null;
}

/** Per-field errors for a set of fields against their current values (only invalid fields present). */
export function fieldErrors(
  fields: FormField[],
  data: SectionData,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of fields) {
    const error = validateField(field, data[field.key] ?? "");
    if (error) errors[field.key] = error;
  }
  return errors;
}

/** True when a section carries at least one required field (so it counts toward completeness). */
export function isSectionRequired(fields: FormField[]): boolean {
  return fields.some((f) => f.required);
}

/**
 * True when a schema section is MANDATORY (always rendered, counts toward completeness), read from the
 * template-declared `FormSection.optional` (M3) -- NOT inferred from field-level required-ness. A
 * missing/undefined `optional` defaults to `false` (= mandatory), matching the frozen M2/M3 default, so
 * a schema that predates M3 behaves as today. Completeness of a mandatory section still uses
 * `isSectionComplete` (every required field valid).
 */
export function isSectionMandatory(section: { optional?: boolean }): boolean {
  return !section.optional;
}

/**
 * Reconcile a stored `activeSections` set (an array of added optional section TITLES) against a fetched
 * `FormSchema`: keep only titles that are still an OPTIONAL section in the schema, preserving the stored
 * order. Drops any title that no longer exists, has become mandatory, or was renamed. Used to restore a
 * client draft's added-optional set safely (M2 keys the compiler's render decision on section title).
 */
export function reconcileActiveSections(
  stored: string[],
  schema: FormSchema | null,
): string[] {
  if (!schema) return [];
  const optionalTitles = new Set(
    schema.sections.filter((s) => !isSectionMandatory(s)).map((s) => s.title),
  );
  return stored.filter((title) => optionalTitles.has(title));
}

/** True when every required field in the section passes client validation. */
export function isSectionComplete(
  fields: FormField[],
  data: SectionData,
): boolean {
  return fields
    .filter((f) => f.required)
    .every((f) => !validateField(f, data[f.key] ?? ""));
}

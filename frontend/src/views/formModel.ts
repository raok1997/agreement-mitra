// Pure, data-independent helpers that turn a fetched FormSchema into the shell's section registry
// and drive client-side validation from field metadata. No Vue, no I/O -- unit-testable in isolation.
// Client validation here is a UX affordance, NOT the trust boundary: authoritative validation of a
// submitted payload is server-side (document-projection CR).

import type { FormField, FormSchema } from "../api/templateForm";
import {
  MAX_YEAR,
  MIN_YEAR,
  parseEntry,
  parseIso,
  type EntryFailure,
} from "./dateEntry";
import {
  FIXED_AMOUNT,
  MAINTENANCE_AMOUNT_KEY,
  MAINTENANCE_MODE_KEY,
} from "./maintenanceTerms";

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
 * minLength/maxLength/pattern, date validity and year range, and enum options membership.
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
    // Plain digits only: `Number` also takes "1e3" and "3500.555", which the server stores and the
    // deed prints verbatim ("INR 1E+3").
    if (field.type === "int" && !/^-?\d+$/.test(value))
      return `${field.label} must be a whole number.`;
    if (field.type === "money" && !/^-?\d+(\.\d{1,2})?$/.test(value))
      return `${field.label} must be an amount in rupees, with at most two decimal places.`;
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
  } else if (field.type === "date") {
    return dateError(field.label, value);
  } else if (field.type === "enum") {
    // Membership is checked on the option VALUE (the label is display-only).
    if (field.options && !field.options.some((o) => o.value === value))
      return `Choose a valid ${field.label}.`;
  }
  return null;
}

const DATE_MESSAGES: Record<EntryFailure, (label: string) => string> = {
  incomplete: (label) => `${label} is incomplete -- enter it as dd/mm/yyyy.`,
  "not-a-date": (label) => `${label} must be a date in dd/mm/yyyy format.`,
  "impossible-date": (label) =>
    `${label} is not a real date -- check the day and month.`,
  "out-of-range": (label) =>
    `${label} must be between ${MIN_YEAR} and ${MAX_YEAR}.`,
};

/**
 * A non-empty date value's error, or null. The widget hands over ISO for a valid entry and the raw
 * typed text otherwise, so anything that is not an in-range ISO date is reported with the reason
 * `parseEntry` gives. Messages name the field and the format, never the entry.
 */
function dateError(label: string, value: string): string | null {
  const iso = parseIso(value);
  if (iso) {
    return iso.year < MIN_YEAR || iso.year > MAX_YEAR
      ? DATE_MESSAGES["out-of-range"](label)
      : null;
  }
  const entry = parseEntry(value);
  // A well-formed dd/mm/yyyy that is not ISO is still not a storable value; the widget never emits one.
  return DATE_MESSAGES[entry.ok ? "not-a-date" : entry.reason](label);
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

/**
 * The tenancy term in WHOLE months between two ISO (`yyyy-mm-dd`) dates, or null when either is
 * missing or unparseable. Complete months only, counting the end date as the tenancy's LAST DAY
 * (inclusive), with a trailing partial month truncated -- 2026-09-01 to 2027-07-31 is 11, and so
 * are 2026-01-01 to 2026-12-01 and 2026-01-01 to 2026-12-20.
 *
 * This mirrors the server's count (java.time.Period.between(start, end + 1 day).toTotalMonths()),
 * which is what the rendered document and the stored agreement both use. The server stays
 * authoritative: this exists so the capture form can show the term as the user tabs between the two
 * date fields, without a round-trip. A drift here would show a wrong number on screen but could
 * never sign a wrong term.
 *
 * Month arithmetic compares y/m/d components, never Date month addition: `new Date(2026, 0, 31)`
 * plus a month silently rolls over to 3 March. Date is used only for the one-day step past the end
 * date, where its rollover is exactly the calendar rule wanted (31 Jan + 1 day = 1 Feb).
 */
export function tenancyMonths(startIso: string, endIso: string): number | null {
  const start = parseIso(startIso);
  const end = parseIso(endIso);
  if (!start || !end) return null;

  const dayAfterEnd = new Date(Date.UTC(end.year, end.month - 1, end.day + 1));
  const endYear = dayAfterEnd.getUTCFullYear();
  const endMonth = dayAfterEnd.getUTCMonth() + 1;
  const endDay = dayAfterEnd.getUTCDate();

  let months = (endYear - start.year) * 12 + (endMonth - start.month);
  const days = endDay - start.day;
  // Period.between: a month only counts once complete, in either direction.
  if (months > 0 && days < 0) months -= 1;
  else if (months < 0 && days > 0) months += 1;
  return months;
}

/**
 * Per-field errors PLUS cross-field rules for a section. `validateField` stays per-field and pure;
 * this is the only place that compares one field against another, so the per-field call sites are
 * unaffected. `isSectionComplete` DOES consult the cross-field rules (see `crossFieldErrors`).
 *
 * The cross-field rules are listed on `crossFieldErrors`. Each error attaches to the field the user
 * can fix, and only once the fields it compares have no error of their own, so a half-filled form
 * reports "required", not a confusing cross-field error.
 *
 * Client validation remains a UX affordance, not the trust boundary: the server independently
 * rejects an end date that is not strictly after the start date with a 400.
 */
export function sectionErrors(
  fields: FormField[],
  data: SectionData,
): Record<string, string> {
  const errors = fieldErrors(fields, data);
  return { ...errors, ...crossFieldErrors(fields, data, errors) };
}

/**
 * The cross-field rules ALONE -- the errors `sectionErrors` adds on top of the per-field ones.
 *
 * Kept separately addressable because the two classes of error block different things. A per-field
 * "required" error must NOT prevent saving a section: capture is progressive, and a customer is
 * expected to fill a section over more than one visit. A cross-field error must, because a section
 * that saves with a reversed date range reaches the preview and compiles a non-positive term into
 * the document body.
 *
 * `perFieldErrors` is passed in so a rule can stay silent while either field still has an error of
 * its own -- a half-filled form reports "required", not a confusing range error. Callers that do
 * not have it to hand may omit it.
 *
 * The rules:
 * - Tenancy date range (every template): the end date must be after the start date.
 * - Fixed maintenance (rental Charges & Utilities): a fixed_amount mode needs an amount above zero.
 *   This is the first TEMPLATE-SPECIFIC rule in this otherwise generic module; the server does not
 *   enforce it (the deed simply drops both Fixed clauses). Such rules move to a template-declared
 *   form condition with the `capture-field-conditional-reveal` follow-up.
 */
export function crossFieldErrors(
  fields: FormField[],
  data: SectionData,
  perFieldErrors: Record<string, string> = fieldErrors(fields, data),
): Record<string, string> {
  const errors: Record<string, string> = {};

  const keys = new Set(fields.map((f) => f.key));
  if (keys.has("startDate") && keys.has("endDate")) {
    const start = parseIso(data.startDate ?? "");
    const end = parseIso(data.endDate ?? "");
    // Only when both parse and neither already has an error of its own.
    if (start && end && !perFieldErrors.startDate && !perFieldErrors.endDate) {
      const endNotAfterStart =
        end.year * 10000 + end.month * 100 + end.day <=
        start.year * 10000 + start.month * 100 + start.day;
      if (endNotAfterStart) {
        errors.endDate = "The end date must be after the start date.";
      }
    }
  }

  if (
    keys.has(MAINTENANCE_MODE_KEY) &&
    keys.has(MAINTENANCE_AMOUNT_KEY) &&
    (data[MAINTENANCE_MODE_KEY] ?? "").trim() === FIXED_AMOUNT &&
    !perFieldErrors[MAINTENANCE_AMOUNT_KEY]
  ) {
    const amount = (data[MAINTENANCE_AMOUNT_KEY] ?? "").trim();
    if (amount === "" || !(Number(amount) > 0)) {
      errors[MAINTENANCE_AMOUNT_KEY] =
        "Enter the monthly amount for a fixed maintenance charge.";
    }
  }
  return errors;
}

/**
 * Whether a section must not be saved as it stands: a cross-field rule fails, or a date, whole-number
 * or money field holds a non-empty value that is invalid. Missing is allowed, wrong is not -- a blank
 * field is reported as required but still saves (capture is progressive), while a wrong one would
 * otherwise reach the preview as raw text, or pass create and then fail draft generation.
 */
export function blocksSave(fields: FormField[], data: SectionData): boolean {
  if (Object.keys(crossFieldErrors(fields, data)).length > 0) return true;
  return fields.some((f) => {
    if (f.readOnly || !BLOCKING_WHEN_INVALID.has(f.type)) return false;
    const value = (data[f.key] ?? "").trim();
    return value !== "" && validateField(f, value) !== null;
  });
}

// A value of these types that is present but invalid would be stored, pass create, and then fail
// draft generation -- after the agreement already exists. Blank is still allowed (progressive capture).
const BLOCKING_WHEN_INVALID = new Set(["date", "int", "money"]);

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
  const everyRequiredFieldFilled = fields
    // A read-only (derived) field is never the customer's to fill, so it can never hold a section
    // back. The backend already projects it required:false, but filtering here too means a stale
    // cached schema cannot strand a section as permanently incomplete.
    .filter((f) => f.required && !f.readOnly)
    .every((f) => !validateField(f, data[f.key] ?? ""));
  if (!everyRequiredFieldFilled) return false;

  // A cross-field rule must hold too, or a section with a reversed date range would report
  // complete and let the flow proceed to a deed stating a non-positive term. Only the CROSS-FIELD
  // rules are added here: per-field errors on OPTIONAL fields deliberately keep their existing
  // (non-blocking) effect on completeness.
  return Object.keys(crossFieldErrors(fields, data)).length === 0;
}

/** True when the customer supplies this field's value -- i.e. it is not a server-derived display. */
export function isCapturedField(field: FormField): boolean {
  return !field.readOnly;
}

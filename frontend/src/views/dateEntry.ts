// Pure date-entry helpers: the single owner of client-side date parsing, formatting and validity.
// No Vue, no DOM. Entry is always day-month-year (dd/mm/yyyy); stored and submitted values are ISO
// (yyyy-mm-dd). The display never goes through a host-locale formatter, and an ISO string is never
// handed to `new Date(iso)` -- that is UTC midnight, which reads as the previous day west of UTC.

/**
 * Accepted year range. Mirrors `PlausibleDates.MIN_YEAR` / `MAX_YEAR` in
 * backend/src/main/java/in/agreementmitra/PlausibleDates.java, which stays authoritative. Kept here
 * because the server cannot be asked per keystroke; boundary tests pin both ends.
 */
export const MIN_YEAR = 1900;
export const MAX_YEAR = 2199;

export interface DateParts {
  year: number;
  month: number;
  day: number;
}

export type EntryFailure =
  "incomplete" | "not-a-date" | "impossible-date" | "out-of-range";

export type EntryResult =
  { ok: true; iso: string } | { ok: false; reason: EntryFailure };

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
const SLASHED = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/;
const DIGITS = /^(\d{2})(\d{2})(\d{4})$/;
// A prefix of either accepted shape: what someone part-way through typing a date holds.
const PARTIAL_SLASHED = /^\d{1,2}(\/(\d{1,2}(\/\d{0,3})?)?)?$/;
const PARTIAL_DIGITS = /^\d{1,7}$/;

/** True when y/m/d name a real calendar day. Round-trips through UTC so the host zone takes no part. */
function isCalendarDate({ year, month, day }: DateParts): boolean {
  const probe = new Date(Date.UTC(year, month - 1, day));
  return (
    probe.getUTCFullYear() === year &&
    probe.getUTCMonth() === month - 1 &&
    probe.getUTCDate() === day
  );
}

function inYearRange(year: number): boolean {
  return year >= MIN_YEAR && year <= MAX_YEAR;
}

function pad(n: number, width: number): string {
  return String(n).padStart(width, "0");
}

export function toIso({ year, month, day }: DateParts): string {
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

/**
 * An ISO `yyyy-mm-dd` string as y/m/d components, or null when it is not a real calendar date.
 * Calendar validity only -- the year bounds are applied by `validateField` and `parseEntry`.
 */
export function parseIso(raw: string): DateParts | null {
  const match = ISO.exec((raw ?? "").trim());
  if (!match) return null;
  const parts = {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
  };
  return isCalendarDate(parts) ? parts : null;
}

/** True when `iso` is a real calendar date inside the accepted year range. */
export function isAcceptedIso(iso: string): boolean {
  const parts = parseIso(iso);
  return parts !== null && inYearRange(parts.year);
}

/**
 * An ISO date as `dd/mm/yyyy`; empty or non-ISO input gives "". Never a locale formatter or
 * `new Date(iso)`: the parts are split from the string, and validity round-trips through UTC.
 */
export function formatIso(iso: string): string {
  const parts = parseIso(iso);
  if (!parts) return "";
  return `${pad(parts.day, 2)}/${pad(parts.month, 2)}/${pad(parts.year, 4)}`;
}

/**
 * A typed `d/m/yyyy`, `dd/mm/yyyy` or eight-digit `ddmmyyyy` entry -- or a pasted ISO `yyyy-mm-dd`,
 * whose year-first order cannot be misread -- as ISO, or the reason it is not one. Never guesses:
 * anything else (`03-02-2001`, `03.02.2001`) is refused, not reinterpreted.
 *
 * Accepting ISO is what keeps the widget's raw-text channel unambiguous: if a well-formed ISO date
 * were refused here, the widget would emit it as raw text, and that text IS a valid stored value.
 */
export function parseEntry(text: string): EntryResult {
  const value = (text ?? "").trim();
  if (value.length > 10) return { ok: false, reason: "not-a-date" };

  const isoMatch = ISO.exec(value);
  const match = isoMatch
    ? [value, isoMatch[3], isoMatch[2], isoMatch[1]]
    : (SLASHED.exec(value) ?? DIGITS.exec(value));
  if (!match) {
    const partial = PARTIAL_SLASHED.test(value) || PARTIAL_DIGITS.test(value);
    return { ok: false, reason: partial || !value ? "incomplete" : "not-a-date" };
  }

  const parts = {
    day: Number(match[1]),
    month: Number(match[2]),
    year: Number(match[3]),
  };
  // Bounds first: Date.UTC maps years 0..99 onto 1900+, so 08/01/0026 would otherwise fail the
  // round trip and be mislabelled an impossible day.
  if (!inYearRange(parts.year)) return { ok: false, reason: "out-of-range" };
  if (!isCalendarDate(parts)) return { ok: false, reason: "impossible-date" };
  return { ok: true, iso: toIso(parts) };
}

// Pure formatting and matching for the "My agreements" list. `now` is always passed in, so the
// relative-time bands are testable without a fake clock.

import type { AgreementSummary } from "../api/agreements";
import type { Role } from "../api/client";
import { formatIso } from "./dateEntry";

const RUPEES = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 0,
});
const RUPEES_AND_PAISE = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * Rupees with Indian digit grouping: 125000 -> "₹1,25,000". Paise show only when there are any
 * (15000.5 -> "₹15,000.50"); the server accepts two decimals, so rounding would misstate a term.
 */
export function formatRupees(amount: number): string {
  return (Number.isInteger(amount) ? RUPEES : RUPEES_AND_PAISE).format(amount);
}

function localMidnight(date: Date): number {
  return new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
  ).getTime();
}

function localIsoDate(date: Date): string {
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${mm}-${dd}`;
}

/** A party's role as the customer reads it; an unknown or missing role reads "Party". */
export function roleLabel(role: Role | string | null | undefined): string {
  return role === "OWNER" ? "Owner" : role === "TENANT" ? "Tenant" : "Party";
}

/** The local calendar date of an instant as dd/mm/yyyy. */
export function formatInstantDate(iso: string): string {
  return formatIso(localIsoDate(new Date(iso)));
}

/**
 * How long ago `iso` was, by the browser's local calendar. A future instant (server clock ahead)
 * reads "just now" rather than a negative age.
 */
export function editedAgo(iso: string, now: Date): string {
  const then = new Date(iso);
  const elapsed = now.getTime() - then.getTime();
  if (elapsed < MINUTE_MS) return "just now";
  if (elapsed < HOUR_MS) return `${Math.floor(elapsed / MINUTE_MS)} min ago`;
  // Rounded: a DST shift makes one calendar day 23 or 25 hours long.
  const days = Math.round((localMidnight(now) - localMidnight(then)) / DAY_MS);
  if (days === 0) return `${Math.floor(elapsed / HOUR_MS)}h ago`;
  if (days === 1) return "yesterday";
  if (days < 30) return `${days} days ago`;
  return `on ${formatIso(localIsoDate(then))}`;
}

/**
 * True when every whitespace-separated term of `query` appears, ignoring case, in any party name,
 * the address or the reference. An empty query matches everything.
 */
export function matchesQuery(
  summary: AgreementSummary,
  query: string,
): boolean {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return true;
  const haystack = [
    ...summary.ownerNames,
    ...summary.tenantNames,
    summary.propertyAddress,
    summary.trackingNumber,
  ]
    .join("\n")
    .toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

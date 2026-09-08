// Which jurisdictions can be stamped and eSigned. Keep all backend calls here, not scattered in
// components. Mirrors the backend `signing.api` route GET /api/jurisdictions.
//
// DISCLOSURE, NOT ENFORCEMENT. The authoritative control is the server-side refusal at finalise,
// checkout, e-stamp intake and eSign initiation. This list exists so a customer sees the limit at
// the point of template selection, rather than filling in a whole agreement and meeting a 409 at
// checkout. A client that ignores it is still refused.

const BASE = "/api";

/** The state codes eligible for paid fulfilment; everything else is draft-and-download only. */
export interface EligibleJurisdictions {
  eligible: string[];
}

/**
 * Fetch the eligible jurisdictions. Anonymous — the endpoint carries no agreement data.
 *
 * Throws on a non-OK response so the caller can decide how to degrade; see `fetchEligibleOrNone`
 * for the marking path, which deliberately degrades to "mark nothing".
 */
export async function fetchEligibleJurisdictions(): Promise<string[]> {
  const res = await fetch(`${BASE}/jurisdictions`, {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    throw new Error(`Could not load eligible jurisdictions (${res.status})`);
  }
  const body = (await res.json()) as EligibleJurisdictions;
  return (body.eligible ?? []).map((code) => code.toUpperCase());
}

/**
 * The same call, degraded for the disclosure path: on any failure it returns `null`, meaning
 * "we don't know, so mark nothing".
 *
 * Marking NOTHING is the deliberate choice over marking everything draft-only. Enforcement is
 * server-side and unaffected either way, so the only question is which wrong answer is worse — and
 * falsely telling a customer in an eligible state that they cannot be stamped would turn a
 * transient network error into a lost sale.
 */
export async function fetchEligibleOrNone(): Promise<string[] | null> {
  try {
    return await fetchEligibleJurisdictions();
  } catch {
    return null;
  }
}

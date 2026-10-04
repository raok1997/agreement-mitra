// The client's copy of the server's RFC 9457 problem types (agreement-error-problem-type-plumbing D1),
// and the one way a type is read from a response and compared. A deliberate copy of the backend's
// `TYPE_*` constants: this is the wire contract, not a duplicated rule.
//
// Types are compared as exact URNs, never by suffix. Nothing here logs a body, a type or an
// exception. Imports nothing, so http.ts can depend on it without a cycle.

export const PROBLEM = {
  jurisdictionUnsupported:
    "urn:agreementmitra:problem:jurisdiction-unsupported",
  draftFrozen: "urn:agreementmitra:problem:draft-frozen",
  contactsFrozen: "urn:agreementmitra:problem:contacts-frozen",
  paymentRequired: "urn:agreementmitra:problem:payment-required",
  csrf: "urn:agreementmitra:problem:csrf",
  renderBusy: "urn:agreementmitra:problem:render-busy",
} as const;

export type ProblemType = (typeof PROBLEM)[keyof typeof PROBLEM];

/**
 * The problem `type` from a response body, or null when the body is not JSON or carries no string
 * `type`. Consumes the body, so it must be the response's last reader. Never throws.
 */
export async function problemTypeOf(res: Response): Promise<string | null> {
  try {
    const body = await res.json();
    return typeof body?.type === "string" ? body.type : null;
  } catch {
    return null;
  }
}

/** Whether `e` is a refusal of exactly `type`, whichever API module threw it. */
export function hasProblemType(e: unknown, type: ProblemType): boolean {
  if (typeof e !== "object" || e === null) return false;
  const problemType = (e as { problemType?: unknown }).problemType;
  return typeof problemType === "string" && problemType === type;
}

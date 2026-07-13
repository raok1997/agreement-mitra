// Thin API client. Keep all backend calls here, not scattered in components.

export interface SignSession {
  providerRequestId: string;
  signingUrl: string;
}

export type Role = "OWNER" | "TENANT";

/** One party (owner or tenant) as captured on the form. Contact is optional at draft. */
export interface PartyInput {
  firstName: string;
  lastName: string;
  fatherName: string;
  currentAddress: string;
  email?: string;
  mobile?: string;
  /** Optional full-name-as-per-Aadhaar override; server derives from first+last when absent. */
  name?: string;
  role: Role;
}

export interface CreateAgreementInput {
  propertyAddress: string;
  monthlyRent: string;
  securityDeposit: string;
  startDate: string; // ISO yyyy-mm-dd
  endDate: string; // ISO yyyy-mm-dd
  signers: PartyInput[];
  /**
   * Optional catalog-selection dimensions from the template picker. When present the server resolves
   * and records the published template for (state, type); the generated draft then renders that
   * selected template (not the default). Omitted keeps the default template behaviour.
   */
  state?: string;
  type?: string;
}

export interface PartyView {
  id: string;
  name: string;
  firstName: string;
  lastName: string;
  fatherName: string;
  currentAddress: string;
  email: string | null;
  mobile: string | null;
  role: Role;
}

export interface AgreementView {
  id: string;
  propertyAddress: string;
  monthlyRent: number;
  securityDeposit: number;
  startDate: string;
  endDate: string;
  durationMonths: number;
  createdAt: string;
  signers: PartyView[];
}

const BASE = "/api";

export async function createAgreement(
  input: CreateAgreementInput,
): Promise<AgreementView> {
  const res = await fetch(`${BASE}/agreements`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw new Error(await describeProblem(res));
  return res.json();
}

interface FieldError {
  field?: string;
  message?: string;
}

/**
 * Build a human message from an RFC 9457 problem+json body. Surfaces the per-field
 * validation errors the backend returns so a 400 says *which* fields are wrong, not just
 * the status. Falls back to the status code if the body is not the expected shape.
 */
async function describeProblem(res: Response): Promise<string> {
  try {
    const body = await res.json();
    const fieldErrors: FieldError[] = Array.isArray(body?.errors) ? body.errors : [];
    if (fieldErrors.length) {
      // Show the (user-friendly) messages the backend supplies; de-duplicate.
      const messages = [
        ...new Set(fieldErrors.map((e) => e.message).filter((m): m is string => !!m)),
      ];
      if (messages.length) return messages.join(" ");
    }
    if (typeof body?.detail === "string") return body.detail;
  } catch {
    // non-JSON body — fall through to the generic message
  }
  return `Sorry, that couldn't be saved (${res.status}). Please check the form and try again.`;
}

/**
 * Fetch the agreement's rental-agreement PDF preview and return an object URL suitable for
 * embedding in an `<iframe>`/`<object>`. The preview is rendered on demand and not stored. The
 * caller owns the returned URL and MUST `URL.revokeObjectURL` it when replacing or unmounting.
 */
export async function fetchAgreementPreview(agreementId: string): Promise<string> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/preview`);
  if (!res.ok) throw new Error(await describeProblem(res));
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

/**
 * Generate the agreement's document and store it as the signable draft (replaces the manual PDF
 * upload for the guided flow). Throws a friendly message on failure -- including the `409` case when
 * the draft is locked because signing has already started.
 */
export async function generateAgreementDocument(agreementId: string): Promise<void> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/document`, { method: "POST" });
  if (!res.ok) throw new Error(await describeProblem(res));
}

export async function requestSignature(agreementId: string): Promise<SignSession> {
  const res = await fetch(`${BASE}/signing/${agreementId}/request`, { method: "POST" });
  if (!res.ok) throw new Error(`Sign request failed: ${res.status}`);
  return res.json();
}

/**
 * Whole months between two ISO dates, exclusive of the end date — mirrors the server's
 * java.time.Period semantics (1 Jan to 1 Dec is 11 months). Returns null for an incomplete or
 * non-positive range so the UI can hide the duration until both dates are valid.
 */
export function durationMonths(startDate: string, endDate: string): number | null {
  if (!startDate || !endDate) return null;
  const s = new Date(startDate);
  const e = new Date(endDate);
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime()) || e <= s) return null;
  let months = (e.getFullYear() - s.getFullYear()) * 12 + (e.getMonth() - s.getMonth());
  if (e.getDate() < s.getDate()) months -= 1;
  return months;
}

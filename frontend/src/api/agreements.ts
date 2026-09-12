// Authenticated agreement calls: the "My Agreements" list, claim (save), read-for-edit, and edit
// (PUT). All attach the Bearer session header (via authStore); anonymous drafting uses none of this.
// Kept here with the other API modules -- components never call fetch directly.

import { type AgreementView, type CreateAgreementInput } from "./client";
import { authHeader } from "./authStore";

const BASE = "/api";

/** The derived display status shown in the list (mirrors the backend AgreementDisplayStatus). */
export type AgreementStatus =
  "DRAFT" | "IN_PROGRESS" | "SIGNED" | "EXPIRED" | "ACTION_NEEDED";

/** A row in "My Agreements": terms-only summary + the derived status and edit-eligibility flag. */
export interface AgreementSummary {
  id: string;
  trackingNumber: string;
  propertyAddress: string;
  monthlyRent: number;
  startDate: string;
  endDate: string;
  durationMonths: number;
  createdAt: string;
  status: AgreementStatus;
  editable: boolean;
}

/**
 * An Error carrying the HTTP status so callers can special-case 401 (session gone) / 409 (frozen).
 *
 * It also carries the RFC 9457 problem `type` where the server sent one, because 409 is not one
 * situation. The terms freeze and the contacts freeze are different lines at different moments -
 * terms lock when the order is placed, contacts only when payment settles - and a client that
 * cannot tell them apart ends up inviting a retry of something that can never succeed.
 */
export class AgreementHttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly problemType: string | null = null,
  ) {
    super(`Agreement request failed: ${status}`);
    this.name = "AgreementHttpError";
  }

  /** Whether the refusal was the contacts freeze: payment is settled, so a retry is futile. */
  get contactsFrozen(): boolean {
    return !!this.problemType?.endsWith("contacts-frozen");
  }

  /**
   * Whether the refusal was the jurisdiction gate: this agreement's state cannot be stamped or
   * eSigned here. Like the contacts freeze, a retry can never succeed -- but for a different
   * reason and with a different remedy, so it needs its own message rather than the generic
   * "could not start payment".
   */
  get jurisdictionUnsupported(): boolean {
    return !!this.problemType?.endsWith("jurisdiction-unsupported");
  }
}

/** Read the problem `type` from an RFC 9457 body, or null when the body is not that shape. */
async function problemTypeOf(res: Response): Promise<string | null> {
  try {
    const body = await res.json();
    return typeof body?.type === "string" ? body.type : null;
  } catch {
    return null;
  }
}

/** List the signed-in caller's agreements, most-recent first. Requires a live session. */
export async function listMyAgreements(): Promise<AgreementSummary[]> {
  const res = await fetch(`${BASE}/agreements`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new AgreementHttpError(res.status);
  return res.json();
}

/** Claim (save) an unowned agreement into the caller's account. Idempotent for the same owner. */
export async function claimAgreement(id: string): Promise<AgreementView> {
  const res = await fetch(`${BASE}/agreements/${id}/claim`, {
    method: "POST",
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new AgreementHttpError(res.status);
  return res.json();
}

/** Read one owned agreement to prefill the edit form. 404 (as AgreementHttpError) if not the owner. */
export async function getAgreement(id: string): Promise<AgreementView> {
  const res = await fetch(`${BASE}/agreements/${id}`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new AgreementHttpError(res.status);
  return res.json();
}

/** What finalising returns: the one reference the customer keeps, plus the resulting order status. */
export interface FinaliseResult {
  agreementId: string;
  trackingReference: string;
  status: string;
}

/**
 * Finalise: place the order and freeze the draft. Idempotent server-side - finalising twice returns
 * the same reference and places no second order, so a retry after a failed payment is safe.
 */
export async function finaliseAgreement(id: string): Promise<FinaliseResult> {
  const res = await fetch(`${BASE}/agreements/${id}/finalise`, {
    method: "POST",
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new AgreementHttpError(res.status);
  return res.json();
}

/** Edit an owned, not-yet-signing agreement (full replace). 409 if a signing request already exists. */
export async function updateAgreement(
  id: string,
  input: CreateAgreementInput,
): Promise<AgreementView> {
  const res = await fetch(`${BASE}/agreements/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeader() },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw new AgreementHttpError(res.status);
  return res.json();
}

/** One party's contact details, as submitted by the pre-payment contact step. */
export interface PartyContactInput {
  signerId: string;
  email: string;
  mobile: string;
}

/**
 * Set party contacts on an agreement, before payment.
 *
 * Deliberately NOT `updateAgreement`. That route replaces terms and the party list wholesale and is
 * owner-scoped, so it cannot serve an anonymous customer - and widening it would hand anyone holding
 * the agreement id the ability to rewrite the rent. This one accepts contacts and nothing else.
 *
 * Usable until PAYMENT settles, not until the order is placed: contacts are not terms, so placing
 * the order does not lock them and a mistyped address can still be corrected. The server refuses it
 * once the agreement is paid or waived, or once it is closed, or when somebody else owns it.
 *
 * No auth header is required, but one is sent when present so a signed-in customer is recognised.
 */
export async function updateAgreementContacts(
  id: string,
  contacts: PartyContactInput[],
): Promise<AgreementView> {
  const res = await fetch(`${BASE}/agreements/${id}/contacts`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", ...authHeader() },
    body: JSON.stringify({ contacts }),
  });
  if (!res.ok) {
    throw new AgreementHttpError(res.status, await problemTypeOf(res));
  }
  return res.json();
}

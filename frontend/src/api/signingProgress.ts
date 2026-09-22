// The status page's reads: per-party signing progress with the fulfilment stage, and the signed
// document download. Both attach the Bearer session when there is one, because a claimed agreement
// answers only its owner -- a link holder without a session sees an unowned agreement, and that is
// the whole ownership rule, decided server-side. Components never call fetch directly.

import { authHeader } from "./authStore";
import type { AgreementStatus } from "./agreements";

const BASE = "/api";

/**
 * Where the agreement stands, at the resolution a customer needs (mirrors the backend
 * FulfilmentStage). Compare against these as a SET -- never by position; STAMP_FAILED is last in
 * the list and is not "past" anything.
 */
export type FulfilmentStage =
  | "NOT_STARTED"
  | "AWAITING_STAMP"
  | "STAMPED"
  | "OUT_FOR_SIGNATURE"
  | "SIGNED"
  | "EXPIRED"
  | "FAILED"
  | "STAMP_FAILED";

/** One party's own signing sub-state (mirrors the backend InviteeStatus). */
export type PartySigningStatus = "PENDING" | "SIGNED" | "REJECTED" | "EXPIRED";

export interface PartyProgress {
  signerId: string;
  /** OWNER / TENANT, or null for a legacy row without one. */
  role: string | null;
  status: PartySigningStatus;
}

/** The server's view of progress. Everything the status page shows about the pipeline comes from here. */
export interface SigningProgress {
  agreementId: string;
  /** The coarse display status the list uses; unchanged, kept for parity. */
  status: AgreementStatus;
  stage: FulfilmentStage;
  /** The polling stop condition, decided server-side. */
  terminal: boolean;
  /**
   * The row can be SIGNED before the signed PDF is stored; the download route 404s until it is.
   * Only offer the download when this is true.
   */
  signedDocumentReady: boolean;
  /** In signing order. */
  parties: PartyProgress[];
}

/** An Error carrying the HTTP status so the page can tell 404 (claimed meanwhile) from the rest. */
export class SigningProgressHttpError extends Error {
  constructor(public readonly status: number) {
    super(`Signing progress request failed: ${status}`);
    this.name = "SigningProgressHttpError";
  }
}

export async function getSigningProgress(
  agreementId: string,
): Promise<SigningProgress> {
  const res = await fetch(`${BASE}/signing/${agreementId}/progress`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new SigningProgressHttpError(res.status);
  return res.json();
}

/**
 * How long a blob URL stays valid after the click that consumes it. The download navigation is
 * asynchronous, so revoking in the same tick can pull the URL out from under it on some browsers.
 */
const REVOKE_AFTER_MS = 1_000;

/**
 * Download the signed document. A fetch, not a link: the session lives in a header, so a bare
 * `<a href>` would reach the server anonymously and be refused for a claimed agreement -- and
 * putting the token in the URL instead would leak it into history and referrers. The bytes are
 * handed to the browser as a short-lived blob URL, revoked once the download has had time to
 * start.
 */
export async function downloadSignedDocument(
  agreementId: string,
  filename = "signed-agreement.pdf",
): Promise<void> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/signed-document`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new SigningProgressHttpError(res.status);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), REVOKE_AFTER_MS);
}

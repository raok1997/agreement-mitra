// Staff e-stamp fulfilment queue: list the orders awaiting a stamp, and upload a purchased
// certificate against one of them. STAFF-only on the server -- this module never decides that, it
// just carries the session header and lets the backend refuse.
//
// THE ROW CARRIES PERSONAL DATA. It used to be non-PII by construction. It no longer is: buying the
// certificate means naming the first party, the second party and the state on the vendor's form, so
// the row carries the template, its state, and every party's name + father's name. Treat it
// accordingly -- it is not a payload to log, echo, or hand to anything but this console.
//
// The upload takes the queue ENTRY's tracking reference, never a value an operator typed. That is
// the point of listing the work: it removes the transcription step entirely rather than relying on
// the reference's check character to catch a slip.

import { authHeader } from "./authStore";

const BASE = "/api";

const QUEUE = `${BASE}/staff/estamp/queue`;
const INTAKE = `${BASE}/staff/estamp`;

/** One party as the queue shows them -- the three fields the vendor's certificate form asks for. */
export interface StampQueueParty {
  /** `OWNER` (first party) or `TENANT` (second party). */
  role: string;
  /** Full name as per Aadhaar -- the name that will appear on the instrument. */
  name: string;
  fatherName: string;
}

/**
 * One outstanding order, carrying what an operator needs to BUY the stamp. Still excluded, on
 * purpose: contact details, rent, deposit, and the full street address (city only).
 */
export interface StampQueueEntry {
  agreementId: string;
  trackingReference: string;
  /**
   * The pinned template's name and state. Both null together when the template cannot be resolved
   * (unpinned, superseded, or archived) -- the row still appears, it just shows less.
   *
   * The STATE is the operative field: it decides which state's stamp paper to buy, and it comes
   * from the template the agreement was drafted against, not from the property address.
   */
  templateName: string | null;
  templateState: string | null;
  /** Every party, owners first. Empty only for an agreement with no signers captured yet. */
  parties: StampQueueParty[];
  /** The property's city, or null when the stored address has no comma-separated tail. */
  propertyCity: string | null;
  agreementStartDate: string;
  awaitingSince: string;
  waitingSeconds: number;
  /**
   * `UNPAID` / `PAID` / `WAIVED`. With the payment gate enforced, an `UNPAID` row is visible here
   * but cannot be stamped - unpaid orders are deliberately NOT filtered out of the queue, so staff
   * can see what is waiting on money rather than wondering where an order went.
   */
  paymentState: string | null;
}

/** The certificate fields a staff member transcribes from the e-stamp they purchased. */
export interface StampCertificateInput {
  certificateNumber: string;
  issueDate: string;
  dutyAmount: string;
  jurisdiction: string;
  descriptionOfDocument?: string;
  purchasedBy?: string;
  /**
   * Also start the Aadhaar eSign workflow once the stamp is attached. Opt-in: it spends a billable
   * transaction and puts a signing invitation in front of both parties, so it is never implied.
   */
  initiateSigning?: boolean;
}

/** What the server echoes back so staff can confirm the certificate landed on the right order. */
export interface StampIntakeResult {
  agreementId: string;
  trackingReference: string;
  propertyCity: string | null;
  agreementStartDate: string;
  /** Last four characters only -- the certificate number evidences duty payment. */
  certificateNumberRedacted: string;
  /**
   * Whether signing started as part of this upload. Reported separately from the stamp outcome,
   * because the two can disagree: a spent certificate is never rolled back to tidy up a result.
   */
  signingInitiated: boolean;
  /** A short fixed token when signing was asked for and did not start; never a vendor message. */
  signingNotStartedReason: string | null;
}

/**
 * An Error carrying the HTTP status so callers can distinguish 401/403 from a 409 refusal.
 *
 * It also carries the RFC 9457 problem `type` where the server sent one, because 409 is no longer a
 * single situation: with the payment gate enforced, "already stamped", "certificate already used"
 * and "nobody has paid" all arrive as 409, and telling an operator the wrong one sends them to fix
 * the wrong thing.
 */
export class StaffQueueHttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly problemType: string | null = null,
  ) {
    super(`Staff queue request failed: ${status}`);
    this.name = "StaffQueueHttpError";
  }

  /** Whether the refusal was the payment gate rather than anything about the certificate. */
  get paymentRequired(): boolean {
    return !!this.problemType?.endsWith("payment-required");
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

/** Orders awaiting a stamp, longest-waiting first. 401/403 for anyone without the STAFF role. */
export async function listStampQueue(): Promise<StampQueueEntry[]> {
  const res = await fetch(QUEUE, { headers: { ...authHeader() } });
  if (!res.ok) throw new StaffQueueHttpError(res.status);
  return res.json();
}

/**
 * Upload a scanned certificate against one queue entry. The agreement is identified by the entry's
 * own tracking reference, so nothing is re-typed and no wrong-agreement stamp is possible by typo.
 */
export async function uploadStampForEntry(
  entry: StampQueueEntry,
  scan: File,
  certificate: StampCertificateInput,
): Promise<StampIntakeResult> {
  const form = new FormData();
  form.append("scan", scan);
  form.append("agreementReference", entry.trackingReference);
  form.append("certificateNumber", certificate.certificateNumber);
  form.append("issueDate", certificate.issueDate);
  form.append("dutyAmount", certificate.dutyAmount);
  form.append("jurisdiction", certificate.jurisdiction);
  if (certificate.descriptionOfDocument) {
    form.append("descriptionOfDocument", certificate.descriptionOfDocument);
  }
  if (certificate.purchasedBy) {
    form.append("purchasedBy", certificate.purchasedBy);
  }
  if (certificate.initiateSigning) {
    form.append("initiateSigning", "true");
  }
  // No Content-Type header: the browser must set the multipart boundary itself.
  const res = await fetch(INTAKE, {
    method: "POST",
    headers: { ...authHeader() },
    body: form,
  });
  if (!res.ok) {
    throw new StaffQueueHttpError(res.status, await problemTypeOf(res));
  }
  return res.json();
}

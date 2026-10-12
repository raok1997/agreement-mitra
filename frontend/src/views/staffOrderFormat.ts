// Every word the staff Orders tab shows for a code the server sent. The server sends codes only;
// the labels and the next step have this one home, so they are tested without a server or a mount.

import { formatMinorUnits } from "../api/payments";
import type { StaffOrderEntry } from "../api/staffOrders";
import { formatInstantDate } from "./agreementListFormat";

const OUTCOME_LABELS: Record<string, string> = {
  RECORDING_FAILED: "Not recorded",
  DUPLICATE_REFERENCE: "Payment id reused",
  AMOUNT_MISMATCH: "Amount mismatch",
  ERRORED: "Error while recording",
};

const STATUS_LABELS: Record<string, string> = {
  CREATED: "Checkout open",
  EXPIRED: "Abandoned",
};

const AGREEMENT_PAYMENT_TEXT: Record<string, string> = {
  UNPAID: "Agreement unpaid",
  WAIVED: "Agreement payment waived",
  PAID_THIS_PAYMENT: "Agreement paid under this payment",
  PAID_OTHER_PAYMENT: "Agreement paid under another payment",
};

/** The runbook section the tab points at. The heading in docs/DEPLOYMENT.md, verbatim. */
export const RUNBOOK_SECTION =
  "A captured payment that was refused or not recorded";

/** A ledger outcome as staff read it; a code without a label is shown as itself. */
export function outcomeLabel(outcome: string): string {
  return OUTCOME_LABELS[outcome] ?? outcome;
}

/** A payment order status as staff read it; a code without a label is shown as itself. */
export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

/** How the order's agreement stands on payment; an unknown code is shown as itself. */
export function agreementPaymentText(agreementPayment: string): string {
  return AGREEMENT_PAYMENT_TEXT[agreementPayment] ?? agreementPayment;
}

/**
 * Minor units as money. Never through Intl's currency style: the currency is a gateway string, and
 * an invalid code makes Intl.NumberFormat throw. INR reads "₹499.00"; anything else reads as the
 * amount followed by the code.
 */
export function formatOrderMoney(
  amountMinorUnits: number,
  currency: string | null,
): string {
  const rupees = formatMinorUnits(amountMinorUnits, "INR");
  if (currency === "INR") return rupees;
  const amount = rupees.replace("₹", "");
  return currency ? `${amount} ${currency}` : amount;
}

/** An instant as local dd/mm/yyyy hh:mm. */
export function formatOrderInstant(iso: string): string {
  const at = new Date(iso);
  const hh = String(at.getHours()).padStart(2, "0");
  const mm = String(at.getMinutes()).padStart(2, "0");
  return `${formatInstantDate(iso)} ${hh}:${mm}`;
}

/** Whether the agreement is already settled some way other than by this order's payment. */
function settledOtherwise(entry: StaffOrderEntry): boolean {
  return (
    entry.agreementPayment === "WAIVED" ||
    entry.agreementPayment === "PAID_OTHER_PAYMENT"
  );
}

/** The agreements holding a reused payment id, as the next step names them. */
function holders(entry: StaffOrderEntry): string {
  return entry.heldBy
    .map((reference) =>
      reference === entry.trackingReference ? "this agreement" : reference,
    )
    .join(", ");
}

const CONFIRM_AND_RECORD =
  "Confirm the capture in the gateway dashboard, then record it by hand under the payment id shown.";

/**
 * The one next step for a paid-but-stuck entry: the first that applies, in the runbook's own order.
 * Null for an entry with no stuck outcome -- a not-paid order has nothing to do.
 *
 * It never says "record it by hand" on an agreement that is already settled another way, and never
 * says a reused payment id is recorded elsewhere when nothing holds it any more.
 */
export function nextStep(entry: StaffOrderEntry): string | null {
  if (!entry.stuckOutcomes.length) return null;
  if (entry.agreementPayment === "PAID_THIS_PAYMENT") {
    return "Already recorded on this agreement. Nothing to record.";
  }
  if (entry.stuckOutcomes.includes("AMOUNT_MISMATCH")) {
    return "Amount differs. Do not record until someone decides to refund or to accept.";
  }
  const reused = entry.stuckOutcomes.includes("DUPLICATE_REFERENCE");
  if (reused && entry.heldBy.length) {
    return `This payment is already recorded on ${holders(entry)}. Do not alter the id to get past the refusal.`;
  }
  if (settledOtherwise(entry)) {
    return "The agreement is already settled. Do not record this payment. Check whether it is a double charge before refunding.";
  }
  if (reused) {
    return `Refused as already recorded, but nothing holds this payment id now. ${CONFIRM_AND_RECORD}`;
  }
  return CONFIRM_AND_RECORD;
}

/**
 * The warning added beside an amount mismatch or a named holder when the agreement is already
 * settled another way, so this captured payment may be a second charge. Null otherwise: with any
 * other next step on a settled agreement, the step itself already says so.
 */
export function doubleChargeNote(entry: StaffOrderEntry): string | null {
  if (!settledOtherwise(entry)) return null;
  const mismatch = entry.stuckOutcomes.includes("AMOUNT_MISMATCH");
  const heldElsewhere =
    entry.stuckOutcomes.includes("DUPLICATE_REFERENCE") &&
    entry.heldBy.length > 0;
  if (!mismatch && !heldElsewhere) return null;
  return entry.agreementPayment === "WAIVED"
    ? "The agreement was waived, so this may be a double charge. Check before refunding."
    : "The agreement is paid under another payment, so this may be a double charge. Check before refunding.";
}

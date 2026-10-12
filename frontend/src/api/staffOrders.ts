// The staff orders view: payment orders that hold a captured payment nobody recorded, and recent
// orders that were not paid. Read-only. STAFF-only on the server -- this module just carries the
// session and lets the backend refuse.
//
// The response carries no agreement id and no party detail. It does carry gateway payment ids:
// staff record a payment by hand under that exact id. Not a payload to log or echo elsewhere.

import { apiFetch } from "./http";

const ORDERS = "/api/staff/payment-orders";

/** One ledger attempt for an order. Codes are sent as stored; labels live in the view's format file. */
export interface StaffOrderAttempt {
  source: string;
  outcome: string;
  /** Null for a gateway event that carried no payment detail. */
  providerPaymentId: string | null;
  amountMinorUnits: number;
  /** As the gateway sent it -- not necessarily a currency code a browser knows. */
  currency: string | null;
  refusedRule: string | null;
  sqlState: string | null;
  firstReceivedAt: string;
  lastReceivedAt: string;
  attemptCount: number;
}

/** `UNPAID` / `WAIVED` / `PAID_THIS_PAYMENT` / `PAID_OTHER_PAYMENT`. */
export type AgreementPayment = string;

export interface StaffOrderEntry {
  trackingReference: string;
  paymentOrderId: string;
  /** A payment order status code, e.g. `CREATED`, `EXPIRED`. */
  status: string;
  amountMinorUnits: number;
  currency: string;
  createdAt: string;
  agreementPayment: AgreementPayment;
  /** The ledger outcomes that make the order stuck. Empty for a not-paid entry. */
  stuckOutcomes: string[];
  /** Tracking references of the agreements already holding a payment id this order was refused for. */
  heldBy: string[];
  /** Oldest first. */
  attempts: StaffOrderAttempt[];
}

export interface StaffOrderGroup {
  entries: StaffOrderEntry[];
  /** True when entries beyond the server's cap were left out. */
  truncated: boolean;
}

export interface StaffOrders {
  stuck: StaffOrderGroup;
  notPaid: StaffOrderGroup;
}

/** An Error carrying the HTTP status, so the view can tell "not staff" from "went wrong". */
export class StaffOrdersHttpError extends Error {
  constructor(public readonly status: number) {
    super(`Staff orders request failed: ${status}`);
    this.name = "StaffOrdersHttpError";
  }
}

/** Both groups. 401/403 for anyone without the STAFF role. */
export async function fetchStaffOrders(): Promise<StaffOrders> {
  const res = await apiFetch(ORDERS);
  if (!res.ok) throw new StaffOrdersHttpError(res.status);
  return res.json();
}

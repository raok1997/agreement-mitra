// The stamp duty quote shown before payment (state-stamp-duty-quoting).
//
// Everything here is computed by the SERVER: the legal duty, its breakdown, and each stamp option
// with the total payable for it. The browser only chooses one of the offered options and, for an
// option below the duty, acknowledges the warning. It never computes or sends an amount of its own.

import { authHeader } from "./authStore";

const BASE = "/api";

export interface StampQuoteLine {
  kind: string;
  label: string;
  /** A plain rupee decimal string from the server, e.g. "1300" or "0.4". */
  amount: string;
}

export interface StampQuoteOption {
  /** Integer minor units (paise). */
  stampValueMinorUnits: number;
  belowDuty: boolean;
  recommended: boolean;
  /** What the customer pays for this option, in paise. Server-calculated; display only. */
  totalMinorUnits: number;
  /** How the stamp value is bought, e.g. "stamp-paper" or "challan". */
  medium: string;
}

export interface StampQuote {
  agreementId: string;
  /** False when the agreement cannot be paid for; only `status` is then meaningful. */
  available: boolean;
  status: string;
  /** True when an order already exists: this is the quote frozen with it. */
  frozen: boolean;
  dutyMinorUnits: number | null;
  currency: string | null;
  breakdown: StampQuoteLine[];
  registrationRequired: boolean | null;
  rule: { id: string; legalReference: string | null; reviewed: boolean } | null;
  /** The warning version a below-duty choice must acknowledge. */
  warningVersion: string | null;
  options: StampQuoteOption[];
}

/** The customer's choice, as sent to checkout. A choice, never an amount to charge. */
export interface StampSelection {
  stampValueMinorUnits: number;
  underStampAcknowledgement?: { warningVersion: string };
}

export class StampQuoteHttpError extends Error {
  constructor(public readonly status: number) {
    super(`Stamp quote request failed: ${status}`);
    this.name = "StampQuoteHttpError";
  }
}

export async function getStampQuote(agreementId: string): Promise<StampQuote> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/stamp-quote`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new StampQuoteHttpError(res.status);
  return res.json();
}

/** The selection for a quote's option, carrying the acknowledgement only when it is below duty. */
export function selectionFor(
  quote: StampQuote,
  option: StampQuoteOption,
): StampSelection {
  return option.belowDuty && quote.warningVersion
    ? {
        stampValueMinorUnits: option.stampValueMinorUnits,
        underStampAcknowledgement: { warningVersion: quote.warningVersion },
      }
    : { stampValueMinorUnits: option.stampValueMinorUnits };
}

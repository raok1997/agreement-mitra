package in.agreementmitra.signing.payment;

/**
 * What applying a payment confirmation actually did. Every caller (webhook, reconciliation, the
 * authoritative read triggered by the browser callback) gets one of these from the <b>same</b> code
 * path - two paths that both write payment state would eventually disagree (design D9).
 *
 * <p>Only {@link #CONFIRMED} is a state change. Everything else is a no-op, which is the point:
 * confirmation has to be idempotent because the provider redelivers webhooks and fires more than
 * one event for a single successful payment.
 */
enum ConfirmationOutcome {

  /** The payment was recorded and the agreement moved to {@code PAID}. Happens exactly once. */
  CONFIRMED,

  /**
   * Already settled - a redelivery, or the second of two events for one payment. Nothing changed.
   */
  ALREADY_CONFIRMED,

  /** No order of ours matches. Nothing changed, and the caller must not reveal that (no oracle). */
  UNKNOWN_ORDER,

  /**
   * The reported amount or currency differs from what the order was placed for (design D8). NOT a
   * successful payment: recording it would credit an agreement for the wrong sum. Surfaced for
   * investigation instead.
   */
  AMOUNT_MISMATCH,

  /**
   * The event carried no provider payment id, so there would be no external reference and nothing
   * to enforce uniqueness on. Left outstanding deliberately: the sibling event, or the
   * reconciliation read, will carry one.
   */
  MISSING_REFERENCE,

  /**
   * The provider payment id is already recorded against some agreement. Raised from the database's
   * unique index, never a read-then-write pre-check, so two concurrent confirmations cannot both
   * credit one payment.
   */
  DUPLICATE_REFERENCE
}

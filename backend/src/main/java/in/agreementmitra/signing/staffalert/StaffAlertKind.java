package in.agreementmitra.signing.staffalert;

import in.agreementmitra.signing.UnrecordedPayment;

/** What a staff alert is about. Stored by name; the message's fixed lead is chosen by it. */
enum StaffAlertKind {

  /** A gateway payment order was paid and the agreement is waiting for its stamp. */
  ORDER_PAID,

  /** A payment was captured on an agreement that already held one - check before refunding. */
  DUPLICATE_PAYMENT,

  /** A captured payment the database refused to record against its order. */
  PAYMENT_NOT_RECORDED,

  /** A captured payment refused because its gateway reference is already recorded elsewhere. */
  PAYMENT_ID_REUSED,

  /** A captured payment refused because its amount or currency differs from the order. */
  PAYMENT_AMOUNT_MISMATCH;

  /** The kind of alert an unrecorded payment raises. */
  static StaffAlertKind of(UnrecordedPayment.Reason reason) {
    // Exhaustive on purpose: a new reason fails the build here.
    return switch (reason) {
      case NOT_RECORDED -> PAYMENT_NOT_RECORDED;
      case ID_REUSED -> PAYMENT_ID_REUSED;
      case AMOUNT_MISMATCH -> PAYMENT_AMOUNT_MISMATCH;
    };
  }
}

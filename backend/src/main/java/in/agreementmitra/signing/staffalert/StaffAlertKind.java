package in.agreementmitra.signing.staffalert;

/** What a staff alert is about. Stored by name; the message's fixed lead is chosen by it. */
enum StaffAlertKind {

  /** A gateway payment order was paid and the agreement is waiting for its stamp. */
  ORDER_PAID,

  /** A payment was captured on an agreement that already held one - check before refunding. */
  DUPLICATE_PAYMENT
}

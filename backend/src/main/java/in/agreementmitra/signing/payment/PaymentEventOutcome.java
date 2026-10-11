package in.agreementmitra.signing.payment;

/**
 * What the payment event ledger records an attempt as: the six {@link ConfirmationOutcome}s, plus
 * the two ways an attempt ends without returning one (payment-event-ledger D8).
 *
 * <p>Those two are deliberately not added to {@link ConfirmationOutcome}, which stays "what a
 * confirmation returned". An attempt recorded with either of them returned nothing; it threw.
 */
enum PaymentEventOutcome {
  CONFIRMED,
  ALREADY_CONFIRMED,
  UNKNOWN_ORDER,
  AMOUNT_MISMATCH,
  MISSING_REFERENCE,
  DUPLICATE_REFERENCE,

  /** The database refused the write for a reason other than the two payment uniqueness rules. */
  RECORDING_FAILED,

  /** The confirmation ended in any other error. */
  ERRORED;

  /** The ledger outcome of the same name. */
  static PaymentEventOutcome of(ConfirmationOutcome outcome) {
    // Exhaustive on purpose: a new confirmation outcome fails the build here. A lookup by name
    // would instead throw after the payment had committed, outside the ledger's own guard.
    return switch (outcome) {
      case CONFIRMED -> CONFIRMED;
      case ALREADY_CONFIRMED -> ALREADY_CONFIRMED;
      case UNKNOWN_ORDER -> UNKNOWN_ORDER;
      case AMOUNT_MISMATCH -> AMOUNT_MISMATCH;
      case MISSING_REFERENCE -> MISSING_REFERENCE;
      case DUPLICATE_REFERENCE -> DUPLICATE_REFERENCE;
    };
  }
}

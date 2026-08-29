package in.agreementmitra.signing;

/**
 * Whether an agreement has been paid for. Server-managed: never settable by a client on create or
 * any other request. Lives at the module root beside {@link SignatureStatus} so any sub-package can
 * name it without reaching into another's internals.
 *
 * <p>{@link #WAIVED} is deliberately <b>not</b> collapsed into {@link #PAID} (design D5). They
 * answer different questions: one says money came in, the other says someone decided to proceed
 * without it. A boolean {@code paid} flag cannot express a deliberate waiver and offers no place to
 * audit who allowed it, so every read and report keeps the two distinguishable.
 */
public enum PaymentState {

  /** Nothing recorded. Every agreement starts here. */
  UNPAID,

  /** Money was received and recorded through the payment-confirmation seam. */
  PAID,

  /** A deliberate decision to proceed without payment (manual override, test, goodwill). */
  WAIVED;

  /**
   * Whether this state lets a gated step proceed when the gate is {@link PaymentGateMode#REQUIRED}
   * - true for {@code PAID} and {@code WAIVED}, false for {@code UNPAID}.
   */
  public boolean satisfiesGate() {
    return this == PAID || this == WAIVED;
  }
}

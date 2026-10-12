package in.agreementmitra.signing;

/**
 * What a reader outside {@code signing.agreement} may know about how an agreement stands on
 * payment. The agreement's stored payment reference is not part of it: a caller asks whether the
 * agreement is paid under a reference it already holds, and never reads the stored one.
 */
public interface AgreementPaymentStanding {

  String trackingReference();

  PaymentState paymentState();

  /**
   * Whether the agreement is {@code PAID} under {@code reference}, compared the way a payment
   * confirmation compares references ({@link PaymentConfirmation#normalizeReference}).
   */
  boolean paidUnder(String reference);
}

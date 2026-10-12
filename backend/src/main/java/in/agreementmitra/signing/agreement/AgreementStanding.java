package in.agreementmitra.signing.agreement;

import in.agreementmitra.signing.AgreementPaymentStanding;
import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.PaymentState;

/**
 * The one implementation of {@link AgreementPaymentStanding}. Package-private, so the stored
 * payment reference it holds cannot be read, or serialised, outside this package.
 */
record AgreementStanding(
    String trackingReference, PaymentState paymentState, String paymentReference)
    implements AgreementPaymentStanding {

  @Override
  public boolean paidUnder(String reference) {
    return paidUnder(paymentState, paymentReference, reference);
  }

  /**
   * The one definition of "paid under this payment": the state is {@code PAID} and both references
   * are present and equal in the confirmation's normal form, so a gateway payment id staff typed by
   * hand in another case is still the same payment. {@link Agreement#paidUnder} calls it too.
   */
  static boolean paidUnder(PaymentState state, String storedReference, String reference) {
    String stored = PaymentConfirmation.normalizeReference(storedReference);
    return state == PaymentState.PAID
        && stored != null
        && stored.equals(PaymentConfirmation.normalizeReference(reference));
  }

  /** Without the reference. */
  @Override
  public String toString() {
    return "AgreementStanding{trackingReference="
        + trackingReference
        + ", paymentState="
        + paymentState
        + "}";
  }
}

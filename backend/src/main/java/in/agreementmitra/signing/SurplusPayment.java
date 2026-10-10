package in.agreementmitra.signing;

import in.agreementmitra.AgreementIds;
import java.util.UUID;

/**
 * A payment order whose payment was captured but not recorded as the agreement's payment, because
 * the agreement already held one.
 *
 * @param paymentOrderId the surplus order
 * @param agreementId the agreement it was placed for
 */
public record SurplusPayment(UUID paymentOrderId, UUID agreementId) {

  /** Both identifiers redacted: the agreement id is a bearer credential. */
  @Override
  public String toString() {
    return "SurplusPayment{paymentOrderId="
        + AgreementIds.redact(paymentOrderId)
        + ", agreementId="
        + AgreementIds.redact(agreementId)
        + "}";
  }
}

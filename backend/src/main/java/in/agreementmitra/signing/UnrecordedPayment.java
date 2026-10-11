package in.agreementmitra.signing;

import in.agreementmitra.AgreementIds;
import java.util.UUID;

/**
 * A payment order with a captured payment that was refused or could not be recorded, and that is
 * still not recorded as paid.
 *
 * <p>"Unrecorded" means not recorded <b>against this order</b>. It says nothing about the
 * agreement, which may already be paid by another order, and for {@link Reason#ID_REUSED} the
 * payment id is already recorded somewhere else.
 *
 * @param paymentOrderId the order the payment was captured for
 * @param agreementId the agreement it was placed for
 * @param reason why the payment is not recorded against the order
 */
public record UnrecordedPayment(UUID paymentOrderId, UUID agreementId, Reason reason) {

  /** Why a captured payment is not recorded against its order. */
  public enum Reason {

    /** The database refused to record it. */
    NOT_RECORDED,

    /** Its gateway payment id is already recorded elsewhere, so it was refused. */
    ID_REUSED,

    /** The amount or currency paid differs from the order, so it was refused. */
    AMOUNT_MISMATCH
  }

  /** Both identifiers redacted: the agreement id is a bearer credential. */
  @Override
  public String toString() {
    return "UnrecordedPayment{paymentOrderId="
        + AgreementIds.redact(paymentOrderId)
        + ", agreementId="
        + AgreementIds.redact(agreementId)
        + ", reason="
        + reason
        + "}";
  }
}

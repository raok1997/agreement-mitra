package in.agreementmitra.signing.agreement;

import in.agreementmitra.AgreementIds;
import java.util.UUID;

/**
 * Published once per agreement, on its <b>first transition out of {@code UNPAID}</b> - to {@code
 * PAID} or to {@code WAIVED}, by the gateway or by a staff member.
 *
 * <p><b>Narrower than its name.</b> It is not published for every payment: not when money arrives
 * on an agreement that was already waived, not for a gateway confirmation of a payment staff
 * already recorded by hand, and not for a surplus payment. A subscriber that needs every payment
 * reads the payment order or the payment event ledger, not this event.
 *
 * <p><b>Why an event rather than a direct call.</b> Three paths settle an agreement - the gateway
 * confirmation, a staff confirmation and a staff waiver - and each must have the same downstream
 * effect. {@code AgreementService} is the one class they share and the place the agreement's row
 * lock is held, so the prior state is read there and the event published there, once.
 *
 * <p><b>After commit, deliberately.</b> Listeners send outbound messages. Running them inside the
 * settling transaction would hold a row lock across a third party's availability, and would send a
 * message about a settlement that could still roll back.
 *
 * @param agreementId the agreement whose payment was settled
 */
public record PaymentSettledEvent(UUID agreementId) {

  @Override
  public String toString() {
    return "PaymentSettledEvent{agreementId=" + AgreementIds.redact(agreementId) + "}";
  }
}

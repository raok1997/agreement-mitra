package in.agreementmitra.signing.recovery;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.PaymentSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the recovery link to every party the moment payment is settled - unprompted (design D5).
 * Settled by any path: a gateway confirmation, a payment a staff member records by hand, or a staff
 * waiver.
 *
 * <p><b>This is the primary fix, not the recovery page.</b> A customer who pays and immediately
 * closes the tab never discovers a recovery form, and asking them to have kept a reference they
 * were shown once is not a plan. Putting the link in their inbox at the moment they pay covers them
 * whether or not they ever go looking.
 *
 * <p><b>Not for a claimed agreement.</b> Anonymous access to one is refused, so the link would not
 * open; its owner signs in. The lookup applies the same "settled and unowned" rule as the
 * reference-entry path.
 *
 * <p>Runs <b>after commit</b>: a message about a payment that could still roll back would be worse
 * than a late one. Swallows everything, because a mail failure must never propagate back into a
 * payment path where the customer's money has already moved, nor fail a staff request whose
 * settlement has already committed.
 */
@Component
class RecoveryOnPaymentListener {

  private static final Logger log = LoggerFactory.getLogger(RecoveryOnPaymentListener.class);

  private final AgreementService agreementService;
  private final RecoveryDeliveryService recoveryDelivery;

  RecoveryOnPaymentListener(
      AgreementService agreementService, RecoveryDeliveryService recoveryDelivery) {
    this.agreementService = agreementService;
    this.recoveryDelivery = recoveryDelivery;
  }

  @TransactionalEventListener
  void onPaymentSettled(PaymentSettledEvent event) {
    try {
      agreementService
          .findRecoverableById(event.agreementId())
          .ifPresent(recoveryDelivery::sendRecoveryLink);
    } catch (RuntimeException failed) {
      // The redacted id and nothing else: a waiver has no payment reference to trace it by, and the
      // exception is left out because its message could carry a recipient's address.
      log.warn(
          "Recovery link could not be sent after payment was settled for agreement {}",
          AgreementIds.redact(event.agreementId()));
    }
  }
}

package in.agreementmitra.signing.recovery;

import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.payment.PaymentConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the recovery link to every party the moment payment is confirmed - unprompted (design D5).
 *
 * <p><b>This is the primary fix, not the recovery page.</b> A customer who pays and immediately
 * closes the tab never discovers a recovery form, and asking them to have kept a reference they
 * were shown once is not a plan. Putting the link in their inbox at the moment they pay covers them
 * whether or not they ever go looking.
 *
 * <p>Runs <b>after commit</b>: a message about a payment that could still roll back would be worse
 * than a late one. Swallows everything, because a mail failure must never propagate back into a
 * payment path where the customer's money has already moved.
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
  void onPaymentConfirmed(PaymentConfirmedEvent event) {
    try {
      agreementService.findById(event.agreementId()).ifPresent(recoveryDelivery::sendRecoveryLink);
    } catch (RuntimeException failed) {
      // No identifier in the log line: this runs on a path where the agreement is already paid, and
      // an operator investigating has the payment record to work from.
      log.warn("Recovery link could not be sent after payment confirmation");
    }
  }
}

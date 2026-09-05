package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.agreement.AgreementService;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>one</b> transactional act of confirming a gateway payment. The webhook, the reconciliation
 * job, and the authoritative read triggered by the browser callback all land here; none of them has
 * its own version of this logic (design D9).
 *
 * <p><b>Why it is a separate bean.</b> The duplicate-reference case surfaces as a {@code
 * DataIntegrityViolationException} from the database's unique index, and that exception must be
 * caught <em>outside</em> the transaction that provoked it - a transaction marked rollback-only
 * cannot be continued. Keeping the transactional method on its own bean means the caller's catch
 * genuinely sits outside the boundary, rather than being silently bypassed by self-invocation.
 *
 * <p><b>Ordering of the two locks.</b> The payment order row is locked first, then the agreement
 * row (inside {@code recordPayment}). Every payment path takes them in that order, so there is no
 * lock-ordering deadlock. The order lock is what makes concurrent deliveries safe: the second
 * delivery blocks, then observes the {@code PAID} the first wrote and applies nothing.
 *
 * <p><b>Never writes gate state directly.</b> The agreement becomes {@code PAID} through {@link
 * AgreementService#recordPayment}, the vendor-neutral confirmation seam - the same one the manual
 * STAFF path uses. Razorpay is a second producer of a {@link PaymentConfirmation}, not a second way
 * to set payment state.
 */
@Service
class PaymentConfirmations {

  private static final Logger log = LoggerFactory.getLogger(PaymentConfirmations.class);

  private final PaymentOrderRepository orders;
  private final AgreementService agreementService;
  private final org.springframework.context.ApplicationEventPublisher events;

  PaymentConfirmations(
      PaymentOrderRepository orders,
      AgreementService agreementService,
      org.springframework.context.ApplicationEventPublisher events) {
    this.orders = orders;
    this.agreementService = agreementService;
    this.events = events;
  }

  /**
   * Apply a confirmation for one provider order.
   *
   * <p>The order of the checks is deliberate: unknown order, then already-settled, then the amount
   * cross-check, and only then the write. Each one is a reason to do nothing, and doing nothing is
   * the common case under redelivery.
   *
   * @param providerOrderId the provider's order id, as reported
   * @param providerPaymentId the provider's payment id - becomes the external reference, which is
   *     unique across all agreements
   * @param reportedMinorUnits the amount reported, in paise
   * @param reportedCurrency the currency reported
   * @throws org.springframework.dao.DataIntegrityViolationException if the payment id is already
   *     recorded against another agreement; the caller maps it to {@link
   *     ConfirmationOutcome#DUPLICATE_REFERENCE} outside this transaction
   */
  @Transactional
  ConfirmationOutcome apply(
      String providerOrderId,
      String providerPaymentId,
      long reportedMinorUnits,
      String reportedCurrency) {
    Optional<PaymentOrder> found = orders.findByProviderOrderIdForUpdate(providerOrderId);
    if (found.isEmpty()) {
      // No order id in the log beyond a redacted fragment, and the caller acknowledges this exactly
      // as it acknowledges a known order - the endpoint is not an existence oracle.
      log.debug("Payment confirmation for an order we do not hold: {}", redact(providerOrderId));
      return ConfirmationOutcome.UNKNOWN_ORDER;
    }
    PaymentOrder order = found.get();
    if (order.status().settled()) {
      log.debug("Payment confirmation already applied for order {}", redact(providerOrderId));
      return ConfirmationOutcome.ALREADY_CONFIRMED;
    }
    if (!order.amount().matches(reportedMinorUnits, reportedCurrency)) {
      // Defence in depth (design D8). Better to refuse and surface it than to silently credit an
      // agreement for a sum it was never invoiced. No amounts in the log line - the fact is enough
      // to start an investigation, and the order row holds the numbers.
      log.warn(
          "Payment confirmation refused: reported amount/currency differs from order {}",
          redact(providerOrderId));
      return ConfirmationOutcome.AMOUNT_MISMATCH;
    }
    if (providerPaymentId == null || providerPaymentId.isBlank()) {
      // Without the provider's payment id there is no external reference, and the uniqueness that
      // stops one payment being credited twice would be lost. Wait for an event that carries it.
      log.warn(
          "Payment confirmation deferred: no provider payment id for order {}",
          redact(providerOrderId));
      return ConfirmationOutcome.MISSING_REFERENCE;
    }

    Instant confirmedAt = Instant.now();
    // Through the seam, never by writing gate state here. The actor is null: this transition was
    // caused by the gateway, not by a staff member, and inventing an identity for it would make the
    // audit trail lie.
    agreementService.recordPayment(
        order.agreementId(),
        new PaymentConfirmation(
            order.agreementId(),
            order.amount().toMajorUnits(),
            order.currency(),
            providerPaymentId,
            confirmedAt),
        null);
    order.markPaid(providerPaymentId, confirmedAt);
    orders.save(order);
    // Published here and nowhere else: this is the one line all three producers converge on, and it
    // is reached exactly once per order because an already-settled order returns above. Listeners
    // run after commit, so nothing outbound happens for a payment that could still roll back.
    events.publishEvent(new PaymentConfirmedEvent(order.agreementId()));
    log.debug("Payment confirmed for order {}", redact(providerOrderId));
    return ConfirmationOutcome.CONFIRMED;
  }

  private static String redact(String id) {
    return RazorpayClient.redact(id);
  }
}

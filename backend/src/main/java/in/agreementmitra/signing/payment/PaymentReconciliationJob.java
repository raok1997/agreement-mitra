package in.agreementmitra.signing.payment;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fallback that stops a paid customer being stranded unpaid.
 *
 * <p>A webhook can be missed: the endpoint is down, the tunnel is not up in local dev, the delivery
 * is dropped. The worst outcome in this whole change is money taken and service withheld, so
 * outstanding orders are re-read from the provider and confirmed if the provider says they were
 * paid.
 *
 * <p><b>It applies confirmation through the same code path as the webhook</b> (design D9). Two
 * paths that both write payment state would eventually disagree; the signing module already
 * establishes this pattern with its own reconciliation job.
 *
 * <p><b>It cannot invent a payment.</b> An order the provider reports unpaid, failed, or expired is
 * left alone or marked accordingly - nothing is confirmed on a guess, and running the job
 * repeatedly changes nothing beyond the first successful confirmation.
 *
 * <p>Bounded: only orders outstanding beyond the configured age, oldest first, at most a batch per
 * run. Disabled in the test profile, where the behaviour is driven by calling {@link #reconcile()}.
 */
@Component
@ConditionalOnProperty(
    prefix = "payment.reconciliation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class PaymentReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

  private final PaymentOrderRepository orders;
  private final PaymentOrderService orderService;
  private final RazorpayClient razorpay;
  private final PaymentProperties properties;

  PaymentReconciliationJob(
      PaymentOrderRepository orders,
      PaymentOrderService orderService,
      RazorpayClient razorpay,
      PaymentProperties properties) {
    this.orders = orders;
    this.orderService = orderService;
    this.razorpay = razorpay;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${payment.reconciliation.interval:PT5M}",
      initialDelayString = "${payment.reconciliation.initial-delay:PT2M}")
  void reconcile() {
    if (!razorpay.apiConfigured()) {
      return; // nothing to read from; not an error, just an unconfigured environment
    }
    Instant now = Instant.now();
    PaymentProperties.Reconciliation config = properties.reconciliation();
    List<PaymentOrder> outstanding =
        orders.findByStatusAndCreatedAtLessThanOrderByCreatedAtAsc(
            PaymentOrderStatus.CREATED,
            now.minus(config.ageThreshold()),
            PageRequest.of(0, config.batchSize()));
    if (outstanding.isEmpty()) {
      return;
    }
    log.debug("Reconciling {} outstanding payment order(s)", outstanding.size());
    for (PaymentOrder order : outstanding) {
      try {
        reconcileOne(order, now);
      } catch (RuntimeException e) {
        // One order's failure must not abort the batch. Details omitted on purpose: no ids, no
        // amounts, nothing that would make a log line worth stealing.
        log.warn("Reconciliation skipped one payment order");
      }
    }
  }

  /**
   * Re-read one order. A provider that reports it paid produces a confirmation through the shared
   * path; anything else leaves it outstanding, or expires it once it is past its useful life so the
   * customer can start a fresh order.
   */
  private void reconcileOne(PaymentOrder order, Instant now) {
    ConfirmationOutcome outcome = orderService.readAuthoritatively(order.providerOrderId());
    if (outcome == ConfirmationOutcome.CONFIRMED) {
      log.debug(
          "Reconciliation recovered a payment for order {}",
          RazorpayClient.redact(order.providerOrderId()));
      return;
    }
    if (outcome != ConfirmationOutcome.UNKNOWN_ORDER) {
      return; // already confirmed / mismatched / duplicate: all handled, none of them our business
    }
    if (order.outstandingLongerThan(properties.order().ttl(), now)) {
      order.markExpired();
      orders.save(order);
    }
  }
}

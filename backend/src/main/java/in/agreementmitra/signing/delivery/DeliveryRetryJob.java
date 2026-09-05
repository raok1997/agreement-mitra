package in.agreementmitra.signing.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled sweep that re-attempts deliveries left pending by a transient failure - a mail provider
 * that was down at the moment of completion, a document that was briefly unreadable, a timeout.
 *
 * <p>It exists because completion happens once and delivery may not: the completion path is what
 * normally triggers a send, so without a sweep a provider outage at exactly the wrong moment would
 * leave a signed agreement sitting undelivered until somebody noticed.
 *
 * <p>Drives rows through the <b>same</b> {@link SignedDocumentDeliveryService} path the completion
 * trigger uses - the claim, the ceiling and the closure rule have exactly one implementation, so
 * this cannot drift out of step with the primary path and cannot double-send.
 *
 * <p>Disabled in the test profile ({@code signing.delivery.retry-enabled=false}); behaviour is
 * tested by invoking {@link SignedDocumentDeliveryService#retryDue()} directly.
 */
@Component
@EnableConfigurationProperties(DeliveryProperties.class)
@ConditionalOnProperty(
    prefix = "signing.delivery",
    name = "retry-enabled",
    havingValue = "true",
    matchIfMissing = true)
class DeliveryRetryJob {

  private static final Logger log = LoggerFactory.getLogger(DeliveryRetryJob.class);

  private final SignedDocumentDeliveryService deliveryService;

  DeliveryRetryJob(SignedDocumentDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
  }

  @Scheduled(
      fixedDelayString = "${signing.delivery.interval:PT5M}",
      initialDelayString = "${signing.delivery.initial-delay:PT2M}")
  void sweep() {
    try {
      deliveryService.retryDue();
    } catch (RuntimeException e) {
      // A sweep failure must never escape into the scheduler. Nothing identifying is logged.
      log.warn("Delivery retry sweep failed; the next run will try again");
    }
  }
}

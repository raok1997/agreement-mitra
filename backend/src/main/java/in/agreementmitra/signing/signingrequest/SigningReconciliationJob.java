package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.SignatureStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled fallback that recovers completions the webhook did not finish — a missed webhook, a
 * Details-API failure that was acked-and-deferred, or a failed artifact download. It drives each
 * selected row through the <em>same</em> {@link SigningRequestService#completeDocument} path the
 * webhook uses (no parallel logic). Bounded by {@link ReconciliationProperties}: oldest-first, at
 * most {@code batchSize} per run. Disabled in the test profile ({@code
 * signing.reconciliation.enabled=false}); behaviour is tested by invoking {@link #reconcile()}.
 */
@Component
@EnableConfigurationProperties(ReconciliationProperties.class)
@ConditionalOnProperty(
    prefix = "signing.reconciliation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class SigningReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(SigningReconciliationJob.class);

  private final SigningRequestRepository repository;
  private final SigningRequestService service;
  private final ReconciliationProperties properties;

  SigningReconciliationJob(
      SigningRequestRepository repository,
      SigningRequestService service,
      ReconciliationProperties properties) {
    this.repository = repository;
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${signing.reconciliation.interval:PT5M}",
      initialDelayString = "${signing.reconciliation.initial-delay:PT1M}")
  void reconcile() {
    Instant now = Instant.now();
    List<String> documentIds =
        repository
            .findRecoverable(
                SignatureStatus.SIGN_REQUESTED,
                now.minus(properties.ageThreshold()),
                SignatureStatus.SIGNED,
                now.minus(properties.grace()),
                PageRequest.of(0, properties.batchSize()))
            .stream()
            .map(SigningRequest::providerDocumentId)
            .filter(id -> id != null)
            .toList();
    if (documentIds.isEmpty()) {
      return;
    }
    log.debug("Reconciling {} recoverable signing request(s)", documentIds.size());
    for (String documentId : documentIds) {
      try {
        service.completeDocument(documentId);
      } catch (RuntimeException e) {
        // One row's failure must not abort the batch; details intentionally omitted (no PII/ids).
        log.warn("Reconciliation skipped one document: completion failed");
      }
    }
  }
}

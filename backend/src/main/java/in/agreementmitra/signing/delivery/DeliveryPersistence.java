package in.agreementmitra.signing.delivery;

import in.agreementmitra.signing.DeliveryArtifact;
import in.agreementmitra.signing.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of the delivery lifecycle, kept as a separate bean so each step is its
 * own short transaction - the provider send happens in {@link SignedDocumentDeliveryService},
 * <em>between</em> {@link #claim} and the outcome step, with no transaction held open across the
 * SMTP round-trip. Same split the signing path already makes around the eSign provider call (D9).
 *
 * <p>That split is not a nicety here. Holding a transaction across a send would mean a rollback
 * could erase the record of a message the provider had already accepted - which is precisely how a
 * legal document gets emailed twice.
 */
@Component
class DeliveryPersistence {

  private static final Logger log = LoggerFactory.getLogger(DeliveryPersistence.class);

  private final SignedDocumentDeliveryRepository repository;

  DeliveryPersistence(SignedDocumentDeliveryRepository repository) {
    this.repository = repository;
  }

  /**
   * Create the delivery record for one recipient if it does not exist yet, in its own transaction.
   *
   * <p><b>Insert-then-catch, never read-then-write.</b> Two concurrent completions would both pass
   * a pre-check and both insert; the unique index on (request, signer, artifact) is what actually
   * makes at most one record exist, and the violation simply means another attempt got there first.
   */
  @Transactional
  void createIfAbsent(SignedDocumentDelivery delivery) {
    try {
      repository.save(delivery);
    } catch (DataIntegrityViolationException alreadyCreated) {
      // A concurrent completion created this recipient's record. Nothing to do, and nothing to log
      // beyond the fact - no signer id, no address.
      log.debug("Delivery record already existed for this recipient; keeping the existing one.");
    }
  }

  /** True when a record already exists for this recipient + artifact. */
  @Transactional(readOnly = true)
  boolean exists(UUID signingRequestId, UUID signerId, DeliveryArtifact artifact) {
    return repository
        .findBySigningRequestIdAndSignerIdAndArtifact(signingRequestId, signerId, artifact)
        .isPresent();
  }

  @Transactional(readOnly = true)
  List<SignedDocumentDelivery> forSigningRequest(UUID signingRequestId) {
    return repository.findBySigningRequestId(signingRequestId);
  }

  @Transactional(readOnly = true)
  List<SignedDocumentDelivery> forAgreement(UUID agreementId) {
    return repository.findByAgreementIdOrderByCreatedAtAsc(agreementId);
  }

  @Transactional(readOnly = true)
  Optional<SignedDocumentDelivery> byId(UUID deliveryId) {
    return repository.findById(deliveryId);
  }

  @Transactional(readOnly = true)
  List<SignedDocumentDelivery> due(Instant now, int limit) {
    return repository.findDue(DeliveryStatus.PENDING, now, PageRequest.of(0, limit));
  }

  /**
   * <b>The exactly-once claim</b> (design D4). One atomic conditional update; {@code true} means
   * this caller now owns the send and everybody else - a re-entering webhook, the reconciliation
   * job, a concurrent completion - will get {@code false} and send nothing.
   */
  @Transactional
  boolean claim(UUID deliveryId, Instant now) {
    return repository.claim(deliveryId, DeliveryStatus.PENDING, DeliveryStatus.IN_PROGRESS, now)
        == 1;
  }

  /** The provider accepted the message. */
  @Transactional
  void markSent(UUID deliveryId, boolean notificationOnly) {
    repository.markSent(deliveryId, DeliveryStatus.SENT, Instant.now(), notificationOnly);
  }

  /** Transient failure: back to {@code PENDING}, due again after the backoff. */
  @Transactional
  void markRetryable(UUID deliveryId, String reason, Instant nextAttemptAt) {
    repository.markRetryable(deliveryId, DeliveryStatus.PENDING, reason, nextAttemptAt);
  }

  /** Permanent failure, or the attempt bound reached: stop retrying and escalate to staff. */
  @Transactional
  void markFailed(UUID deliveryId, String reason) {
    repository.markFailed(deliveryId, DeliveryStatus.FAILED, reason);
  }

  /**
   * Re-arm a row for a deliberate, attributed staff re-send. Returns {@code false} when the row is
   * in flight or has no resolvable address - neither is something a re-send can fix.
   */
  @Transactional
  boolean rearmForResend(UUID deliveryId, UUID actorIdentityId) {
    return repository.rearmForResend(
            deliveryId,
            DeliveryStatus.PENDING,
            DeliveryStatus.IN_PROGRESS,
            actorIdentityId,
            Instant.now())
        == 1;
  }
}

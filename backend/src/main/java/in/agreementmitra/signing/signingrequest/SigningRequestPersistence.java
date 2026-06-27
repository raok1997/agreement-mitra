package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.StampInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional persistence steps of the signing-request lifecycle, kept as a separate bean so
 * each step is its own short transaction — the provider HTTP call happens in {@link
 * SigningRequestService}, <em>between</em> {@link #createPending} and {@link #markRequested}, with
 * no transaction held open across the network round-trip (design D9).
 */
@Component
class SigningRequestPersistence {

  private static final Logger log = LoggerFactory.getLogger(SigningRequestPersistence.class);

  private final SigningRequestRepository repository;
  private final AgreementService agreementService;

  SigningRequestPersistence(
      SigningRequestRepository repository, AgreementService agreementService) {
    this.repository = repository;
    this.agreementService = agreementService;
  }

  /**
   * tx1: persist the pre-request row before the provider call, so a later failure is recoverable.
   */
  @Transactional
  UUID createPending(UUID agreementId) {
    SigningRequest request = SigningRequest.createPending(agreementId);
    return repository.save(request).getId();
  }

  /**
   * Stamp step (fresh procurement): attach the procured stamp data to the agreement and advance the
   * request to {@code STAMPED} in one short transaction (so both commit together), <em>after</em>
   * the stamped PDF has already been written to object storage outside any transaction (D9 — no tx
   * spans the blob put or the later provider call).
   */
  @Transactional
  void markStamped(UUID signingRequestId, UUID agreementId, StampInfo stampInfo) {
    agreementService.attachStamp(agreementId, stampInfo);
    markStamped(signingRequestId);
  }

  /** Stamp step (reuse of an already-stamped agreement): advance the request to {@code STAMPED}. */
  @Transactional
  void markStamped(UUID signingRequestId) {
    SigningRequest request = load(signingRequestId);
    request.markStamped();
    repository.save(request);
  }

  /**
   * Drive the request to the terminal {@code STAMP_FAILED} (stamping failed; provider not called).
   */
  @Transactional
  void markStampFailed(UUID signingRequestId) {
    SigningRequest request = load(signingRequestId);
    request.markStampFailed();
    repository.save(request);
  }

  private SigningRequest load(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("Signing request vanished: " + id));
  }

  /** tx2: attach the provider document id + per-signer URLs and move to {@code SIGN_REQUESTED}. */
  @Transactional
  void markRequested(UUID id, String providerDocumentId, List<SigningRequestInvitee> inviteeRows) {
    SigningRequest request =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("Signing request vanished: " + id));
    request.markRequested(providerDocumentId, inviteeRows);
    repository.save(request);
  }

  /**
   * Apply the authoritative per-invitee statuses and drive the aggregate FSM (tx_a). No-op for an
   * unknown document id (the webhook must stay indistinguishable). Optimistic-lock contention from
   * a concurrent delivery is swallowed — the winning transition already applied a single legal
   * terminal state.
   *
   * @return the signing-request id needing artifact download (present iff now {@code SIGNED} with
   *     no artifact keys yet), so the caller can fetch+store outside this transaction; empty
   *     otherwise.
   */
  @Transactional
  Optional<UUID> applyAuthoritativeStatus(String providerDocumentId, DocumentStatusView view) {
    var maybe = repository.findByProviderDocumentId(providerDocumentId);
    if (maybe.isEmpty()) {
      return Optional.empty(); // unknown document — indistinguishable no-op
    }
    try {
      SigningRequest request = maybe.get();
      request.applyInviteeStatuses(view);
      repository.save(request);
      return request.needsArtifacts() ? Optional.of(request.getId()) : Optional.empty();
    } catch (ObjectOptimisticLockingFailureException e) {
      // A concurrent delivery already committed the transition; nothing to do.
      log.debug("Concurrent transition lost the race for one document; ignoring.");
      return Optional.empty();
    }
  }

  /**
   * Record the downloaded artifacts' object keys (tx_b), idempotently: a no-op if keys are already
   * set (a concurrent winner stored them) so a re-delivery never double-records. A genuine
   * download/storage failure is surfaced by the caller before this runs (keys stay null →
   * reconciliation retries); only benign lock contention is swallowed here.
   */
  @Transactional
  void storeArtifactKeys(UUID signingRequestId, String signedPdfKey, String auditTrailKey) {
    SigningRequest request =
        repository
            .findById(signingRequestId)
            .orElseThrow(
                () -> new IllegalStateException("Signing request vanished: " + signingRequestId));
    if (request.signedPdfKey() != null) {
      return; // already stored by a concurrent winner — idempotent no-op
    }
    try {
      request.storeArtifactKeys(signedPdfKey, auditTrailKey);
      repository.save(request);
    } catch (ObjectOptimisticLockingFailureException e) {
      log.debug("Concurrent artifact-key persist lost the race; ignoring.");
    }
  }
}

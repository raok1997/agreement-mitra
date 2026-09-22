package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.StampInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
  private final WebhookKeyCipher webhookKeyCipher;

  SigningRequestPersistence(
      SigningRequestRepository repository,
      AgreementService agreementService,
      WebhookKeyCipher webhookKeyCipher) {
    this.repository = repository;
    this.agreementService = agreementService;
    this.webhookKeyCipher = webhookKeyCipher;
  }

  /**
   * <b>Order placement.</b> Create the agreement's signing request in the durable {@code
   * PDF_GENERATED} state, or return the existing one. This runs when the CUSTOMER finalises - that
   * is the moment the order exists, the terms freeze, and the customer's involvement ends. Stamp
   * intake never creates this row; it only advances it.
   *
   * <p>Idempotent by design: a double-submitted finalise must not produce a second order, so an
   * existing request in any state is returned unchanged.
   */
  @Transactional
  UUID placeOrder(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .map(SigningRequest::getId)
        .orElseGet(() -> repository.save(SigningRequest.createPending(agreementId)).getId());
  }

  /**
   * The row a stamp upload may act on: the agreement's request <b>only if</b> it is resting in
   * {@code PDF_GENERATED}. Empty when the agreement was never finalised (no order placed) or has
   * already moved on. Intake deliberately cannot create the row - the order is placed by the
   * customer at finalisation, so an upload against a non-finalised agreement is a mistake worth
   * refusing rather than silently repairing.
   */
  @Transactional(readOnly = true)
  Optional<UUID> awaitingStampRequestId(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .filter(request -> request.status() == SignatureStatus.PDF_GENERATED)
        .map(SigningRequest::getId);
  }

  /**
   * The stamp-intake work queue: every request resting in {@code PDF_GENERATED}, longest-waiting
   * first, bounded by {@code limit}. Excludes stamped, signed, closed and terminally-failed work by
   * construction -- those rows are in some other state, so completed and dead orders can never
   * accumulate here.
   */
  @Transactional(readOnly = true)
  List<AwaitingStamp> awaitingStampQueue(int limit) {
    return repository
        .findByStatusOrderByCreatedAtAsc(SignatureStatus.PDF_GENERATED, PageRequest.of(0, limit))
        .stream()
        .map(request -> new AwaitingStamp(request.agreementId(), request.createdAt()))
        .toList();
  }

  /** One queue row from the signing side: which agreement, and since when it has been waiting. */
  record AwaitingStamp(UUID agreementId, Instant awaitingSince) {}

  /** The agreement's current signing status, or empty when no signing request exists yet. */
  @Transactional(readOnly = true)
  Optional<SignatureStatus> currentStatus(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .map(SigningRequest::status);
  }

  /**
   * The {@code STAMPED} row the signing flow must advance, or empty when the agreement has none in
   * that state. The signing flow only ever <em>reads</em> the stamp state; the {@code STAMPED}
   * transition itself belongs to the staff upload (design D2).
   */
  @Transactional(readOnly = true)
  Optional<UUID> stampedRequestId(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .filter(request -> request.status() == SignatureStatus.STAMPED)
        .map(SigningRequest::getId);
  }

  /**
   * Stamp intake: attach the uploaded certificate's data to the agreement and advance the request
   * to {@code STAMPED} in one short transaction (so both commit together), <em>after</em> the scan
   * and the stamped PDF have already been written to object storage outside any transaction (D9 -
   * no tx spans a blob put or the later provider call).
   *
   * <p>The database's unique index on the normalised certificate number fires here: if the
   * certificate was already spent on another agreement, this transaction rolls back in full and the
   * caller maps the violation to 409. That is deliberate -- a read-then-write pre-check would let
   * two concurrent uploads both pass.
   */
  @Transactional
  void markStamped(UUID signingRequestId, UUID agreementId, StampInfo stampInfo) {
    agreementService.attachStamp(agreementId, stampInfo);
    markStamped(signingRequestId);
  }

  /** Advance the request to {@code STAMPED}. */
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

  /**
   * tx2: attach the provider document id + per-signer URLs and move to {@code SIGN_REQUESTED}.
   *
   * <p>{@code webhookKey} is the provider's per-transaction webhook credential (null for a provider
   * that issues none). It is <b>encrypted here</b>, on the way in, so the plaintext never reaches
   * the database and a database read never yields a working webhook credential (design D3).
   */
  @Transactional
  void markRequested(
      UUID id,
      String providerDocumentId,
      List<SigningRequestInvitee> inviteeRows,
      String webhookKey) {
    SigningRequest request =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("Signing request vanished: " + id));
    request.markRequested(providerDocumentId, inviteeRows, webhookKeyCipher.encrypt(webhookKey));
    repository.save(request);
  }

  /**
   * The decrypted per-transaction webhook credential for a provider transaction id, or empty when
   * the transaction is unknown or the provider issued no such key.
   *
   * <p>The <b>module</b> owns this lookup, not the adapter: a per-transaction secret cannot be
   * checked from configuration, but handing the adapter a repository would put persistence inside
   * the vendor boundary and make the adapter stateful (design D1). So the module loads it and hands
   * the plaintext back for a constant-time comparison, and nothing is logged either way.
   */
  @Transactional(readOnly = true)
  Optional<String> webhookKeyFor(String providerDocumentId) {
    return repository
        .findByProviderDocumentId(providerDocumentId)
        .map(SigningRequest::webhookSecurityKey)
        .map(webhookKeyCipher::decrypt);
  }

  /**
   * The agreement's signing progress: the aggregate FSM state plus each invitee's own sub-state,
   * keyed by canonical signer id. Empty when the agreement has no signing request yet.
   */
  @Transactional(readOnly = true)
  Optional<Progress> progressFor(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .map(
            request -> {
              Map<UUID, InviteeStatus> bySigner = new LinkedHashMap<>();
              request.invitees().forEach(i -> bySigner.put(i.signerId(), i.status()));
              return new Progress(request.status(), bySigner, request.signedPdfKey() != null);
            });
  }

  /**
   * The aggregate signing state plus per-signer sub-states, and whether the signed PDF has landed
   * (the row goes {@code SIGNED} before the artifacts are fetched). Carries no PII, no signing URL,
   * and no storage key -- only the fact that one exists.
   */
  record Progress(
      SignatureStatus status, Map<UUID, InviteeStatus> statusBySignerId, boolean signedPdfStored) {}

  /**
   * The provider transaction id of the agreement's request, <b>only while it is still pending</b>
   * ({@code SIGN_REQUESTED}). Backs extend / re-invite: both operate on an in-flight transaction,
   * and neither is meaningful once it has reached a terminal state.
   */
  @Transactional(readOnly = true)
  Optional<String> pendingProviderDocumentId(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .filter(request -> request.status() == SignatureStatus.SIGN_REQUESTED)
        .map(SigningRequest::providerDocumentId);
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

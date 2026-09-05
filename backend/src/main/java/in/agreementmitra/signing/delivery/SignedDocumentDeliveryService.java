package in.agreementmitra.signing.delivery;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.DeliveryArtifact;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.SigningCompletionView;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.DeliveryStatusResponse;
import in.agreementmitra.signing.mail.AttachmentCeiling;
import in.agreementmitra.signing.mail.RecipientRedaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Delivers the signed agreement to each party and closes the agreement once every party has it.
 *
 * <p>Five constraints shape everything here:
 *
 * <ol>
 *   <li><b>Only signing-verified addresses.</b> A party is emailed at the address their invitation
 *       was issued to and at which they then completed signing. A draft-time address is never a
 *       fallback: the signed PDF names both parties, the property and the money, plus a stamp
 *       certificate naming both, so a mistyped draft address would disclose all of it to a
 *       stranger, irreversibly. A party with no verified address is escalated, never guessed at
 *       (design D2).
 *   <li><b>Exactly once per recipient.</b> This service is entered from the shared completion path,
 *       which BOTH the webhook and the reconciliation job re-run, and may also run concurrently. So
 *       each recipient's record is <b>claimed by a guarded conditional update before the message is
 *       built</b>; re-entry finds it claimed and sends nothing (design D4).
 *   <li><b>Never block or roll back completion.</b> A signature is a legal fact that has already
 *       happened. Every entry point swallows its own failures, so a dead mail provider leaves the
 *       artifacts stored and the completion recorded, with delivery merely pending retry (design
 *       D5).
 *   <li><b>Never touch the signing FSM.</b> Nothing here can move a signing request off {@code
 *       SIGNED}. A bounced mailbox does not un-sign an agreement.
 *   <li><b>Never email the audit trail.</b> Only the signed agreement is attached.
 * </ol>
 *
 * <p>Deliberately NOT {@code @Transactional}: the transactional steps live in {@link
 * DeliveryPersistence} so no transaction spans the SMTP round-trip, and so a rollback can never
 * erase the record of a message the provider already accepted.
 */
@Service
@EnableConfigurationProperties(DeliveryProperties.class)
public class SignedDocumentDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(SignedDocumentDeliveryService.class);

  /** Fixed failure tokens. Constants, never derived from a provider message or from input. */
  private static final String REASON_ARTIFACT_UNAVAILABLE = "signed-document-unavailable";

  private static final String REASON_RETRY_LIMIT = "retry-limit-reached";

  private final SigningRequestQuery signingRequestQuery;
  private final AgreementService agreementService;
  private final DeliveryPersistence persistence;
  private final BlobStore blobStore;
  private final EmailSender emailSender;
  private final AttachmentCeiling attachmentCeiling;
  private final DeliveryProperties properties;

  SignedDocumentDeliveryService(
      SigningRequestQuery signingRequestQuery,
      AgreementService agreementService,
      DeliveryPersistence persistence,
      BlobStore blobStore,
      EmailSender emailSender,
      AttachmentCeiling attachmentCeiling,
      DeliveryProperties properties) {
    this.signingRequestQuery = signingRequestQuery;
    this.agreementService = agreementService;
    this.persistence = persistence;
    this.blobStore = blobStore;
    this.emailSender = emailSender;
    this.attachmentCeiling = attachmentCeiling;
    this.properties = properties;
  }

  /**
   * The fulfilment step of the shared completion path, reached identically by the webhook and by
   * the reconciliation job. Runs after the artifacts are stored.
   *
   * <p><b>This method never throws.</b> Completion has already been recorded by the time it runs,
   * and letting a mail outage propagate here would let it look like a completion failure - or
   * worse, roll one back.
   */
  public void onSigningCompleted(String providerDocumentId) {
    try {
      signingRequestQuery.completionViewFor(providerDocumentId).ifPresent(this::fulfil);
    } catch (RuntimeException e) {
      // No document id, no address, no payload. Completion is unaffected; the retry sweep will
      // pick up anything left pending.
      log.warn("Delivery step failed after completion; completion and artifacts are unaffected");
    }
  }

  /**
   * Drive delivery + closure for one signing request. Idempotent by construction: creating records
   * is guarded by a unique index, sending is guarded by the claim, and closing is guarded by the
   * aggregate.
   */
  void fulfil(SigningCompletionView view) {
    if (isTerminalFailure(view.status())) {
      // Dead work must leave the queue rather than sitting in it forever, indistinguishable from
      // work still in progress. Nothing was produced, so this closes as abandoned - a state that
      // stays distinguishable from completed in every read.
      closeAbandoned(view);
      return;
    }
    if (!view.deliverable()) {
      return; // not signed yet, or the artifacts are not stored - nothing to deliver
    }
    ensureRecords(view);
    deliverOutstanding(view);
    evaluateClosure(view);
  }

  /**
   * Create one delivery record per party, resolving each to its signing-verified address. Runs on
   * every entry (it is cheap and idempotent) so a record missing for any reason is repaired rather
   * than lost.
   */
  private void ensureRecords(SigningCompletionView view) {
    for (SigningCompletionView.Party party : view.parties()) {
      if (persistence.exists(
          view.signingRequestId(), party.signerId(), DeliveryArtifact.SIGNED_AGREEMENT)) {
        continue;
      }
      Optional<String> verified = party.verifiedEmail();
      if (verified.isEmpty()) {
        // No proof this party owns any address we hold. Record the condition and surface it for
        // staff; do NOT substitute the draft-time address.
        log.warn("No signing-verified address for a party; delivery recorded as unresolvable");
        persistence.createIfAbsent(
            SignedDocumentDelivery.unresolvable(
                view.agreementId(),
                view.signingRequestId(),
                party.signerId(),
                DeliveryArtifact.SIGNED_AGREEMENT));
        continue;
      }
      persistence.createIfAbsent(
          SignedDocumentDelivery.pending(
              view.agreementId(),
              view.signingRequestId(),
              party.signerId(),
              DeliveryArtifact.SIGNED_AGREEMENT,
              verified.get()));
    }
  }

  /** Attempt every claimable record for this signing request. */
  private void deliverOutstanding(SigningCompletionView view) {
    Instant now = Instant.now();
    List<SignedDocumentDelivery> candidates =
        persistence.forSigningRequest(view.signingRequestId()).stream()
            .filter(row -> row.status().claimable())
            .filter(row -> row.nextAttemptAt() == null || !row.nextAttemptAt().isAfter(now))
            .toList();
    if (candidates.isEmpty()) {
      return; // everything already sent, failed, unresolvable, or not yet due
    }
    String reference = trackingReferenceFor(view.agreementId());
    for (SignedDocumentDelivery row : candidates) {
      attempt(row, view.signedPdfKey(), reference);
    }
  }

  /**
   * One delivery attempt. <b>Claim first</b>: everything after the claim - reading the document,
   * building the message, handing it to the provider - happens only for the caller that won the
   * conditional update, so a re-entered completion path and a concurrent attempt cannot both send.
   */
  private void attempt(SignedDocumentDelivery row, String signedPdfKey, String reference) {
    UUID deliveryId = row.getId();
    if (!persistence.claim(deliveryId, Instant.now())) {
      log.debug("Delivery already claimed by another attempt; sending nothing.");
      return;
    }
    // The claim incremented the counter, so this is attempt number (loaded + 1).
    int attemptNumber = row.attempts() + 1;
    byte[] signedPdf;
    try {
      signedPdf = blobStore.get(signedPdfKey);
    } catch (RuntimeException e) {
      // The document we are supposed to deliver is not readable right now. Retryable: nothing about
      // the recipient is wrong.
      recordFailure(deliveryId, attemptNumber, REASON_ARTIFACT_UNAVAILABLE, false);
      return;
    }
    boolean oversize = attachmentCeiling.exceededBy(signedPdf.length);
    EmailMessage message =
        oversize
            ? DeliveryMessages.notificationOnly(row.recipientEmail(), reference)
            : DeliveryMessages.withAttachment(row.recipientEmail(), reference, signedPdf);
    try {
      emailSender.send(message);
    } catch (EmailDeliveryException e) {
      recordFailure(deliveryId, attemptNumber, e.reason(), e.permanent());
      return;
    } catch (RuntimeException e) {
      // An unclassified seam failure. Treat as transient: the attempt bound turns a wrong guess
      // into a delay, whereas guessing permanent would strand a deliverable document.
      recordFailure(deliveryId, attemptNumber, "delivery-failed", false);
      return;
    }
    persistence.markSent(deliveryId, oversize);
    log.info(
        "Signed agreement delivered to {} (attachment: {})",
        RecipientRedaction.redact(row.recipientEmail()),
        !oversize);
  }

  /**
   * Record the outcome of a failed attempt. Permanent failures stop immediately and escalate;
   * transient ones retry with doubling backoff until the attempt bound, at which point they also
   * escalate - because a document nobody looks at is a document that never arrives.
   */
  private void recordFailure(
      UUID deliveryId, int attemptNumber, String reason, boolean permanentFailure) {
    if (permanentFailure) {
      persistence.markFailed(deliveryId, reason);
      log.warn("Delivery permanently failed and needs staff attention: {}", reason);
      return;
    }
    if (attemptNumber >= properties.maxAttempts()) {
      persistence.markFailed(deliveryId, REASON_RETRY_LIMIT);
      log.warn("Delivery exhausted its attempts and needs staff attention: {}", reason);
      return;
    }
    Instant nextAttemptAt = Instant.now().plus(properties.backoffAfter(attemptNumber));
    persistence.markRetryable(deliveryId, reason, nextAttemptAt);
    log.debug(
        "Delivery attempt {} failed transiently ({}); scheduled for retry", attemptNumber, reason);
  }

  /**
   * Close the agreement once - and only once - every party has actually been delivered to.
   *
   * <p>Partial delivery deliberately does not close: an agreement delivered to the owner but not
   * the tenant has outstanding work, and closing it would hide exactly the case somebody needs to
   * fix.
   */
  private void evaluateClosure(SigningCompletionView view) {
    List<SignedDocumentDelivery> rows = persistence.forSigningRequest(view.signingRequestId());
    if (rows.isEmpty() || rows.size() < view.parties().size()) {
      return; // no records, or not every party has one yet
    }
    if (rows.stream().anyMatch(row -> row.status().outstanding())) {
      return; // somebody has not received it
    }
    if (agreementService.close(view.agreementId(), ClosureReason.COMPLETED)) {
      log.info(
          "Agreement {} closed as completed: signed and delivered to every party",
          view.agreementId());
    }
  }

  /** Terminal signing failure closes as abandoned, with the reason kept distinguishable. */
  private void closeAbandoned(SigningCompletionView view) {
    ClosureReason reason =
        view.status() == SignatureStatus.EXPIRED
            ? ClosureReason.ABANDONED_SIGNING_EXPIRED
            : ClosureReason.ABANDONED_SIGNING_FAILED;
    if (agreementService.close(view.agreementId(), reason)) {
      log.info("Agreement {} closed as abandoned ({})", view.agreementId(), reason);
    }
  }

  private static boolean isTerminalFailure(SignatureStatus status) {
    return status == SignatureStatus.FAILED
        || status == SignatureStatus.EXPIRED
        || status == SignatureStatus.STAMP_FAILED;
  }

  /**
   * The agreement's own tracking reference, for the message body. Blank when unavailable - the
   * message is still worth sending without it, and a delivery must never fail over a display value.
   */
  private String trackingReferenceFor(UUID agreementId) {
    return agreementService.findById(agreementId).map(AgreementResponse::trackingNumber).orElse("");
  }

  // --- entry points other than the completion path ---------------------------

  /**
   * The bounded retry sweep: re-attempt every delivery whose backoff has elapsed. Each row is
   * driven through the <b>same</b> {@link #fulfil} path the completion trigger uses, so there is no
   * second implementation of the claim, the ceiling, or the closure rule to drift out of step.
   */
  void retryDue() {
    List<SignedDocumentDelivery> due = persistence.due(Instant.now(), properties.batchSize());
    if (due.isEmpty()) {
      return;
    }
    due.stream()
        .map(SignedDocumentDelivery::agreementId)
        .distinct()
        .forEach(
            agreementId -> {
              try {
                signingRequestQuery.completionViewForAgreement(agreementId).ifPresent(this::fulfil);
              } catch (RuntimeException e) {
                // One agreement's failure must not abort the sweep. No ids, no addresses.
                log.warn("Delivery retry skipped one agreement: the attempt failed");
              }
            });
  }

  /**
   * A deliberate staff re-send for one recipient, recorded as a <b>separate, attributed attempt</b>
   * rather than as an automatic retry - the two mean different things to whoever reads the record
   * later.
   *
   * <p>Permitted on a closed agreement: closure means "no work outstanding", not "no longer
   * available", and re-sending a document that has already been delivered advances nothing.
   *
   * @throws ResourceNotFoundException if there is no such delivery record
   */
  public List<DeliveryStatusResponse> resend(UUID deliveryId, UUID actorIdentityId) {
    SignedDocumentDelivery row =
        persistence
            .byId(deliveryId)
            .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + deliveryId));
    if (persistence.rearmForResend(deliveryId, actorIdentityId)) {
      signingRequestQuery.completionViewForAgreement(row.agreementId()).ifPresent(this::fulfil);
    }
    return statusFor(row.agreementId());
  }

  /**
   * The staff diagnostic view for one agreement: which party, what state, how many attempts, and
   * the failure reason - enough to act on a failure without exposing the document itself. The
   * recipient address is <b>redacted</b>, because knowing which party is stuck does not require
   * reading their mailbox out of a support screen.
   */
  public List<DeliveryStatusResponse> statusFor(UUID agreementId) {
    return persistence.forAgreement(agreementId).stream()
        .map(
            row ->
                new DeliveryStatusResponse(
                    row.getId(),
                    row.signerId(),
                    row.artifact().name(),
                    RecipientRedaction.redact(row.recipientEmail()),
                    row.status().name(),
                    row.status().needsAttention(),
                    row.attempts(),
                    row.lastError(),
                    row.notificationOnly(),
                    row.sentAt(),
                    row.resendCount(),
                    row.resentAt()))
        .toList();
  }
}

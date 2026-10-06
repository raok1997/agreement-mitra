package in.agreementmitra.signing.agreement;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SigningRequestQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ingests the user's uploaded rental-agreement draft PDF and attaches it to the agreement. Java-
 * {@code public} so the {@code api} controller (a sibling package) can inject it; Modulith-internal
 * to the signing module.
 *
 * <p>The upload is untrusted: bytes are validated by magic signature (not the declared content
 * type), never parsed/rendered, never logged, and the attacker-controlled filename is never used.
 * Storage key is derived from the parsed {@code UUID} (no path traversal). Side-effect order is
 * fixed — <b>owner-check → validate → freeze-check → store → attach</b> — so a rejected upload
 * never overwrites the stored blob, and a non-owner learns nothing about the agreement's state.
 */
@Service
public class DraftService {

  private static final Logger log = LoggerFactory.getLogger(DraftService.class);

  /** The PDF magic signature: {@code %PDF-}. Content-Type is untrusted and ignored. */
  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

  private static final String CONTENT_TYPE_PDF = "application/pdf";

  private static final String DRAFT_PREFIX = "drafts/";

  /** Characters in a canonical UUID's text form. */
  private static final int UUID_LENGTH = 36;

  private final AgreementRepository repository;
  private final BlobStore blobStore;
  private final SigningRequestQuery signingRequestQuery;
  private final PaymentOrderQuery paymentOrderQuery;
  private final AgreementDeletionRepository deletions;

  DraftService(
      AgreementRepository repository,
      BlobStore blobStore,
      SigningRequestQuery signingRequestQuery,
      PaymentOrderQuery paymentOrderQuery,
      AgreementDeletionRepository deletions) {
    this.repository = repository;
    this.blobStore = blobStore;
    this.signingRequestQuery = signingRequestQuery;
    this.paymentOrderQuery = paymentOrderQuery;
    this.deletions = deletions;
  }

  /**
   * Every object key a draft-stage agreement can own - the single list, so delete cannot miss one.
   * The first is the draft PDF key {@link #attachDraft} writes. A change that adds a draft-stage
   * key adds it here.
   */
  static List<String> draftStageKeys(UUID agreementId) {
    return List.of(draftKey(agreementId));
  }

  /**
   * The storage prefix of every key {@link #draftStageKeys} can return - what the orphan sweep
   * lists. A change that adds a draft-stage key under a new prefix adds the prefix here.
   */
  static List<String> draftStagePrefixes() {
    return List.of(DRAFT_PREFIX);
  }

  /**
   * The agreement id whose draft-stage key {@code key} is exactly, or empty. Takes the 36
   * characters after a {@link #draftStagePrefixes prefix} as the candidate id and accepts it only
   * if {@link #draftStageKeys} of that id contains {@code key}, so the key format has one
   * definition and a non-canonical spelling (upper case, another extension, a stray file) is
   * rejected.
   */
  static Optional<UUID> draftIdOf(String key) {
    for (String prefix : draftStagePrefixes()) {
      if (!key.startsWith(prefix) || key.length() < prefix.length() + UUID_LENGTH) {
        continue;
      }
      try {
        UUID id = UUID.fromString(key.substring(prefix.length(), prefix.length() + UUID_LENGTH));
        if (draftStageKeys(id).contains(key)) {
          return Optional.of(id);
        }
      } catch (IllegalArgumentException notAUuid) {
        // not a draft-stage key
      }
    }
    return Optional.empty();
  }

  private static String draftKey(UUID agreementId) {
    return DRAFT_PREFIX + agreementId + ".pdf";
  }

  /**
   * Validate and store {@code bytes} as the agreement's draft, then record the storage key on the
   * aggregate.
   *
   * <p>Owner-scoped once claimed ({@link Agreement#admits}): a non-owner gets the same 404 as an
   * unknown id, before any other check. The row is loaded under the write lock {@code claim} takes,
   * so an upload and a claim serialise. The blob write stays under that lock because the storage
   * key is fixed per agreement. Work that depends only on the bytes (parsing, conversion) belongs
   * <b>before</b> this call, never inside it, so it does not hold the lock.
   *
   * @param callerIdentityId the authenticated caller, or null when anonymous
   * @throws ResourceNotFoundException if the agreement does not exist or is claimed by someone else
   *     (mapped to 404)
   * @throws InvalidUploadException if the bytes are not a PDF or are empty (mapped to 400)
   * @throws ConflictException if a signing request already exists — the draft is finalized (409)
   */
  @Transactional
  public void attachDraft(UUID agreementId, UUID callerIdentityId, byte[] bytes) {
    Agreement agreement =
        repository
            .findByIdForUpdate(agreementId)
            .filter(a -> a.admits(callerIdentityId))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Agreement not found: " + AgreementIds.redact(agreementId)));

    validatePdf(bytes);

    if (signingRequestQuery.existsForAgreement(agreementId)) {
      throw ConflictException.draftFrozen();
    }

    String key = draftKey(agreementId);
    blobStore.put(key, bytes, CONTENT_TYPE_PDF);
    agreement.attachDraft(key); // managed entity — flushed on tx commit
    agreement.markEdited(Instant.now());

    log.debug("Draft stored for agreement {}", AgreementIds.redact(agreementId));
  }

  /**
   * Delete an unpaid draft the caller owns: the agreement and its parties in this transaction, a
   * PII-free {@link AgreementDeletion} record in the same one, and the draft-stage objects after it
   * commits.
   *
   * <p>Strict owner rule (as for an edit, not {@link Agreement#admits}): an unowned agreement is
   * never deletable here. Unknown, unowned and another identity's agreement are one 404, decided
   * before the deletability check, so the endpoint is no ownership oracle. The row is loaded under
   * a full {@code FOR UPDATE} lock ({@link AgreementRepository#findByIdForDelete}); a signing
   * request or payment order insert takes {@code FOR KEY SHARE} on it, so one committed first is
   * seen by the existence checks below, and one that waits fails its FK.
   *
   * <p>Objects are removed by their derived keys, never the nullable draft column (an edit clears
   * it without removing the object), after commit so a rolled-back delete keeps its PDF. A failed
   * removal is logged and swallowed: it leaves an unreferenced private object, never a dangling
   * database reference.
   *
   * @throws ResourceNotFoundException unknown, unowned, or another identity's agreement (404)
   * @throws ConflictException {@code DRAFT_NOT_DELETABLE} when it is no longer an unpaid draft
   *     (409)
   */
  @Transactional
  public void deleteDraft(UUID agreementId, UUID callerIdentityId) {
    Agreement agreement =
        repository
            .findByIdForDelete(agreementId)
            .filter(a -> callerIdentityId != null && callerIdentityId.equals(a.ownerIdentityId()))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Agreement not found: " + AgreementIds.redact(agreementId)));

    if (!agreement.isDeletableDraft(
        signingRequestQuery.existsForAgreement(agreementId),
        paymentOrderQuery.existsForAgreement(agreementId))) {
      throw ConflictException.draftNotDeletable();
    }

    remove(agreement, DeletionReason.OWNER_DELETE);
    log.debug("Draft deleted for agreement {}", AgreementIds.redact(agreementId));
  }

  /**
   * Purge the agreement if, under its row lock, it is still an unpaid draft last edited before
   * {@code cutoff} (stale-draft-purge D1, D2b). The lock is {@code SKIP LOCKED}: a row another
   * transaction holds is left for the next run, never waited for. Removes exactly what {@link
   * #deleteDraft} removes, through the same core, with a {@code RETENTION_PURGE} record.
   *
   * <p>Package-private: {@code DraftService} is Java-public for the {@code api} controller, and
   * nothing outside this package may purge with an arbitrary cutoff.
   *
   * @return true if the agreement was purged; false if it is absent, locked, edited since the
   *     cutoff, or no longer an unpaid draft
   */
  @Transactional
  boolean purgeIfStale(UUID agreementId, Instant cutoff) {
    Optional<Agreement> locked = repository.findByIdForPurge(agreementId);
    if (locked.isEmpty()) {
      return false;
    }
    Agreement agreement = locked.get();
    if (!agreement.lastEditedAt().isBefore(cutoff)
        || !agreement.isDeletableDraft(
            signingRequestQuery.existsForAgreement(agreementId),
            paymentOrderQuery.existsForAgreement(agreementId))) {
      return false;
    }
    remove(agreement, DeletionReason.RETENTION_PURGE);
    return true;
  }

  /**
   * The one delete core: the agreement and its parties, the deletion record stamped now, and the
   * draft-stage objects after commit. Callers hold the row lock and have decided deletability.
   */
  private void remove(Agreement agreement, DeletionReason reason) {
    UUID agreementId = agreement.getId();
    repository.delete(agreement); // parties cascade
    deletions.save(AgreementDeletion.of(agreement, reason, Instant.now()));

    List<String> keys = draftStageKeys(agreementId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              removeObjects(agreementId, keys);
            }
          });
    } else {
      log.warn(
          "No transaction synchronization; removing draft objects inline for agreement {}",
          AgreementIds.redact(agreementId));
      removeObjects(agreementId, keys);
    }
  }

  /**
   * Best effort. Logs the cause's class name only - never the throwable, whose storage-client cause
   * can carry the raw object name.
   */
  private void removeObjects(UUID agreementId, List<String> keys) {
    for (String key : keys) {
      try {
        blobStore.delete(key);
      } catch (RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log.warn(
            "Draft object removal failed for agreement {} ({})",
            AgreementIds.redact(agreementId),
            cause.getClass().getSimpleName());
      }
    }
  }

  /**
   * Reject empty, sub-signature, or non-{@code %PDF-} content. Never inspects beyond the header.
   */
  private static void validatePdf(byte[] bytes) {
    if (bytes == null || bytes.length < PDF_MAGIC.length) {
      throw new InvalidUploadException("upload is empty or shorter than the PDF signature");
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (bytes[i] != PDF_MAGIC[i]) {
        throw new InvalidUploadException("upload is not a PDF (magic-byte mismatch)");
      }
    }
  }
}

package in.agreementmitra.signing.agreement;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.SigningRequestQuery;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  private final AgreementRepository repository;
  private final BlobStore blobStore;
  private final SigningRequestQuery signingRequestQuery;

  DraftService(
      AgreementRepository repository,
      BlobStore blobStore,
      SigningRequestQuery signingRequestQuery) {
    this.repository = repository;
    this.blobStore = blobStore;
    this.signingRequestQuery = signingRequestQuery;
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

    String key = "drafts/" + agreementId + ".pdf";
    blobStore.put(key, bytes, CONTENT_TYPE_PDF);
    agreement.attachDraft(key); // managed entity — flushed on tx commit
    agreement.markEdited(Instant.now());

    log.debug("Draft stored for agreement {}", AgreementIds.redact(agreementId));
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

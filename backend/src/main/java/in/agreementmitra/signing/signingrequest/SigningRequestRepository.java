package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.SignatureStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the {@link SigningRequest} aggregate. Package-private — Modulith-internal. */
interface SigningRequestRepository extends JpaRepository<SigningRequest, UUID> {

  /** Look up by the vendor document id (unique). Used by the webhook + reconciliation paths. */
  Optional<SigningRequest> findByProviderDocumentId(String providerDocumentId);

  /** True if any signing request exists for the agreement. Drives the draft freeze (CR-5). */
  boolean existsByAgreementId(UUID agreementId);

  /**
   * Recoverable rows for the reconciliation scan, oldest-first and bounded by {@code pageable}:
   * stale {@code SIGN_REQUESTED} rows (a possibly-missed webhook) past {@code staleBefore}, plus
   * {@code SIGNED} rows with no artifact key yet (a failed download) past {@code graceBefore} (so a
   * just-completed row mid-download in the webhook path is not raced). {@code PDF_GENERATED}
   * orphans are excluded — they have no document id to reconcile.
   */
  @Query(
      "select sr from SigningRequest sr where "
          + "(sr.status = :requested and sr.createdAt < :staleBefore) or "
          + "(sr.status = :signed and sr.signedPdfKey is null and sr.createdAt < :graceBefore) "
          + "order by sr.createdAt asc")
  List<SigningRequest> findRecoverable(
      @Param("requested") SignatureStatus requested,
      @Param("staleBefore") Instant staleBefore,
      @Param("signed") SignatureStatus signed,
      @Param("graceBefore") Instant graceBefore,
      Pageable pageable);
}

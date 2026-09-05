package in.agreementmitra.signing.agreement;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the {@link Agreement} aggregate. Package-private — Modulith-internal. */
interface AgreementRepository extends JpaRepository<Agreement, UUID> {

  /**
   * The agreements owned by {@code ownerIdentityId}, most-recent first -- backs the "list mine"
   * (resume) projection. Filters on the indexed {@code owner_identity_id}; an unowned (null-owner)
   * draft never matches, so it is never listed to anyone.
   */
  List<Agreement> findByOwnerIdentityIdOrderByCreatedAtDesc(UUID ownerIdentityId);

  /**
   * Resolve the agreement named by its persisted {@link TrackingReference}. The column carries a
   * database-level unique constraint, so this resolves to at most one row -- unlike the
   * display-only tracking number, which is derived at render time and is not collision-free.
   */
  Optional<Agreement> findByTrackingReference(String trackingReference);

  /**
   * Load an agreement with a row-level write lock, for the check-and-set of claim/edit: the lock
   * serializes two concurrent claims so they cannot both observe an unowned row and both win (D2).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Agreement a where a.id = :id")
  Optional<Agreement> findByIdForUpdate(@Param("id") UUID id);
}

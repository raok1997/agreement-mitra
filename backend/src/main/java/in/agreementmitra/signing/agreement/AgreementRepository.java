package in.agreementmitra.signing.agreement;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the {@link Agreement} aggregate. Package-private — Modulith-internal. */
interface AgreementRepository extends JpaRepository<Agreement, UUID> {

  /**
   * The agreements owned by {@code ownerIdentityId}, most recently edited first (ties: newest
   * created, then id) -- backs the "list mine" (resume) projection. Filters on the indexed {@code
   * owner_identity_id}; an unowned (null-owner) draft never matches, so it is never listed to
   * anyone. The parties are fetched in the same query (one bag, no pagination), ordered by the
   * collection's {@code @OrderBy}, so the summary's names cost no query per row.
   */
  @EntityGraph(attributePaths = "signers")
  List<Agreement> findByOwnerIdentityIdOrderByLastEditedAtDescCreatedAtDescIdDesc(
      UUID ownerIdentityId);

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

  /**
   * Load an agreement under a full {@code FOR UPDATE} lock, for the owner's delete. Not {@link
   * #findByIdForUpdate}: Hibernate renders {@code PESSIMISTIC_WRITE} on PostgreSQL as {@code FOR NO
   * KEY UPDATE}, which does not conflict with the {@code FOR KEY SHARE} a child-row insert takes on
   * its parent. Only {@code FOR UPDATE} makes a concurrent signing-request or payment-order insert
   * and the delete serialise, so the delete either sees the committed child or blocks it.
   */
  @Query(value = "SELECT * FROM agreement WHERE id = :id FOR UPDATE", nativeQuery = true)
  Optional<Agreement> findByIdForDelete(@Param("id") UUID id);

  /**
   * Load an agreement under {@code FOR UPDATE SKIP LOCKED}, for the retention purge (stale-draft-
   * purge D2b): the same full lock as {@link #findByIdForDelete}, but a row another transaction
   * holds - an edit, a claim, a finalise, an in-flight child insert, another instance's purge - is
   * returned empty instead of waited for. The purge never queues behind a user request.
   */
  @Query(
      value = "SELECT * FROM agreement WHERE id = :id FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  Optional<Agreement> findByIdForPurge(@Param("id") UUID id);

  /**
   * One keyset page of retention-purge candidates: {@code (id, last_edited_at)} of unpaid open
   * agreements last edited before {@code cutoff}, with no signing request and no payment order,
   * strictly after the cursor {@code (afterEditedAt, afterId)}, oldest first.
   *
   * <p>A deliberate <b>pre-filter copy</b> of {@link Agreement#isDeletableDraft} (D2): it only
   * narrows the set, and the decision is re-made under the row lock in {@code
   * DraftService.purgeIfStale}, so drift cannot cause a wrong delete. Change both together. The
   * state literals stay inline so the planner matches the V25 partial index. The cursor must never
   * be NULL (a NULL row comparison selects nothing); start at {@code (EPOCH, 0-UUID)}.
   */
  @Query(
      value =
          """
          SELECT a.id, a.last_edited_at FROM agreement a
          WHERE a.payment_state = 'UNPAID' AND a.closure_state = 'OPEN'
            AND a.last_edited_at < :cutoff
            AND (a.last_edited_at, a.id) > (:afterEditedAt, :afterId)
            AND NOT EXISTS (SELECT 1 FROM signing_request s WHERE s.agreement_id = a.id)
            AND NOT EXISTS (SELECT 1 FROM payment_order p WHERE p.agreement_id = a.id)
          ORDER BY a.last_edited_at, a.id
          LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findStaleDraftCandidates(
      @Param("cutoff") Instant cutoff,
      @Param("afterEditedAt") Instant afterEditedAt,
      @Param("afterId") UUID afterId,
      @Param("limit") int limit);
}

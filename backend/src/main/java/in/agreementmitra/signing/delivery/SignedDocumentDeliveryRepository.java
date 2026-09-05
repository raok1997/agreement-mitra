package in.agreementmitra.signing.delivery;

import in.agreementmitra.signing.DeliveryArtifact;
import in.agreementmitra.signing.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link SignedDocumentDelivery}. Package-private - Modulith-internal.
 *
 * <p>Every state transition here is a <b>single conditional statement</b>, not a load-mutate-save.
 * That is deliberate: the claim in particular has to be atomic, because the completion path is
 * re-entered by both the webhook and the reconciliation job and may also run concurrently. A
 * read-then-write claim would let two attempts both observe {@code PENDING} and both send - which
 * means emailing a legal document twice.
 */
interface SignedDocumentDeliveryRepository extends JpaRepository<SignedDocumentDelivery, UUID> {

  List<SignedDocumentDelivery> findByAgreementIdOrderByCreatedAtAsc(UUID agreementId);

  List<SignedDocumentDelivery> findBySigningRequestId(UUID signingRequestId);

  Optional<SignedDocumentDelivery> findBySigningRequestIdAndSignerIdAndArtifact(
      UUID signingRequestId, UUID signerId, DeliveryArtifact artifact);

  /**
   * <b>The exactly-once claim.</b> Moves one row {@code PENDING -> IN_PROGRESS} and counts the
   * attempt, but only if it is still {@code PENDING} and its backoff has elapsed. Returns the
   * number of rows changed: {@code 1} means this caller owns the send, {@code 0} means somebody
   * else already claimed it (or it is not due yet, or it has already been sent) and this caller
   * must send nothing.
   *
   * <p>The version column is bumped explicitly - a bulk update does not drive {@code @Version}
   * itself - so an entity loaded before this statement cannot later overwrite it.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SignedDocumentDelivery d set d.status = :claimed, d.attempts = d.attempts + 1,"
          + " d.version = d.version + 1"
          + " where d.id = :id and d.status = :expected"
          + " and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)")
  int claim(
      @Param("id") UUID id,
      @Param("expected") DeliveryStatus expected,
      @Param("claimed") DeliveryStatus claimed,
      @Param("now") Instant now);

  /**
   * The provider accepted the message. Terminal on the happy path. {@code notificationOnly} records
   * whether the document itself went out or only a pointer at the in-app copy (the oversize
   * fallback) - a distinction a support conversation turns on.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SignedDocumentDelivery d set d.status = :sent, d.sentAt = :now, d.lastError = null,"
          + " d.nextAttemptAt = null, d.notificationOnly = :notificationOnly,"
          + " d.version = d.version + 1"
          + " where d.id = :id")
  int markSent(
      @Param("id") UUID id,
      @Param("sent") DeliveryStatus sent,
      @Param("now") Instant now,
      @Param("notificationOnly") boolean notificationOnly);

  /** A transient failure: back to {@code PENDING}, due again after the backoff. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SignedDocumentDelivery d set d.status = :pending, d.lastError = :reason,"
          + " d.nextAttemptAt = :nextAttemptAt, d.version = d.version + 1"
          + " where d.id = :id")
  int markRetryable(
      @Param("id") UUID id,
      @Param("pending") DeliveryStatus pending,
      @Param("reason") String reason,
      @Param("nextAttemptAt") Instant nextAttemptAt);

  /** A permanent failure, or the attempt bound reached. Stops retrying and escalates to staff. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SignedDocumentDelivery d set d.status = :failed, d.lastError = :reason,"
          + " d.nextAttemptAt = null, d.version = d.version + 1"
          + " where d.id = :id")
  int markFailed(
      @Param("id") UUID id, @Param("failed") DeliveryStatus failed, @Param("reason") String reason);

  /**
   * Re-arm a row for a deliberate, attributed staff re-send. Records who asked and when, and
   * increments the re-send counter so a re-send is never mistaken for an automatic retry. Applies
   * only to a row that is not already in flight.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SignedDocumentDelivery d set d.status = :pending, d.nextAttemptAt = :now,"
          + " d.resendCount = d.resendCount + 1, d.resentByIdentityId = :actor, d.resentAt = :now,"
          + " d.version = d.version + 1"
          + " where d.id = :id and d.status <> :inProgress and d.recipientEmail is not null")
  int rearmForResend(
      @Param("id") UUID id,
      @Param("pending") DeliveryStatus pending,
      @Param("inProgress") DeliveryStatus inProgress,
      @Param("actor") UUID actor,
      @Param("now") Instant now);

  /**
   * Rows due for another attempt, oldest-first and bounded - the retry sweep. Only {@code PENDING}
   * rows whose backoff has elapsed; {@code SENT}, {@code FAILED} and {@code UNRESOLVABLE} are never
   * swept, and an {@code IN_PROGRESS} row belongs to somebody else.
   */
  @Query(
      "select d from SignedDocumentDelivery d where d.status = :pending"
          + " and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)"
          + " order by d.createdAt asc")
  List<SignedDocumentDelivery> findDue(
      @Param("pending") DeliveryStatus pending, @Param("now") Instant now, Pageable pageable);
}

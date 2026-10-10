package in.agreementmitra.signing.staffalert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link StaffAlert}. Package-private - Modulith-internal.
 *
 * <p>Extends the bare {@link Repository}, not {@code JpaRepository}: with an assigned id and no
 * version column, an inherited {@code save()} would merge a stale copy over state that only the
 * conditional updates below may change. Every mutation here is one guarded statement.
 *
 * <p>The status is written as a literal in every query rather than bound, so the planner can use
 * the partial index on pending rows (V27).
 */
interface StaffAlertRepository extends Repository<StaffAlert, UUID> {

  Optional<StaffAlert> findById(UUID agreementId);

  /**
   * Record a pending alert unless the agreement already has one, in any status. One statement, so
   * there is nothing to catch. {@code 'PENDING'} is a SQL literal because Hibernate does not bind a
   * Java enum to a native parameter as its name.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO staff_alert (agreement_id, status, attempts, next_attempt_at, created_at)"
              + " VALUES (:agreementId, 'PENDING', :attempts, :nextAttemptAt, :createdAt)"
              + " ON CONFLICT (agreement_id) DO NOTHING",
      nativeQuery = true)
  int insertPendingIfAbsent(
      @Param("agreementId") UUID agreementId,
      @Param("attempts") int attempts,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("createdAt") Instant createdAt);

  /** Pending alerts whose next attempt is due, oldest first and bounded. */
  @Query(
      "select a.agreementId from StaffAlert a"
          + " where a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.PENDING"
          + " and a.nextAttemptAt <= :now"
          + " order by a.nextAttemptAt asc, a.agreementId asc")
  List<UUID> findDueAgreementIds(@Param("now") Instant now, Pageable pageable);

  /**
   * <b>The claim.</b> Counts the attempt and pushes the next-attempt time out, but only while the
   * row is still pending, still due, and still at the attempt count the caller read. {@code 1}
   * means this caller owns the send; {@code 0} means another run got there first.
   *
   * <p>The new next-attempt time is the lease: nobody else can claim the row until it passes, and a
   * caller that crashes mid-send needs no recovery step. It is computed in Java ({@link
   * StaffAlertBackoff}), never here.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update StaffAlert a set a.attempts = :claimed, a.nextAttemptAt = :nextAttemptAt"
          + " where a.agreementId = :agreementId"
          + " and a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.PENDING"
          + " and a.attempts = :seen and a.nextAttemptAt <= :now")
  int claim(
      @Param("agreementId") UUID agreementId,
      @Param("seen") int seen,
      @Param("claimed") int claimed,
      @Param("now") Instant now,
      @Param("nextAttemptAt") Instant nextAttemptAt);

  /** The channel accepted the message. Applies only to the attempt the caller claimed. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update StaffAlert a"
          + " set a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.SENT,"
          + " a.sentAt = :sentAt"
          + " where a.agreementId = :agreementId"
          + " and a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.PENDING"
          + " and a.attempts = :attempts")
  int markSent(
      @Param("agreementId") UUID agreementId,
      @Param("attempts") int attempts,
      @Param("sentAt") Instant sentAt);

  /**
   * Stop: a permanent refusal, the last attempt failing, or an alert closed by the expiry step.
   * Guarded by the attempt count the caller holds, so it cannot fail a row another run has since
   * claimed.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update StaffAlert a"
          + " set a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.FAILED"
          + " where a.agreementId = :agreementId"
          + " and a.status = in.agreementmitra.signing.staffalert.StaffAlertStatus.PENDING"
          + " and a.attempts = :attempts")
  int markFailed(@Param("agreementId") UUID agreementId, @Param("attempts") int attempts);
}

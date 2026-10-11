package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.SurplusPayment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link PaymentOrder}. Package-private - Modulith-internal. */
interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

  /**
   * Load an order by the provider's id with a row-level <b>write lock</b>. This is the concurrency
   * control for confirmation: two simultaneous webhook deliveries for one order serialize here, so
   * the second observes the {@code PAID} status the first wrote and applies nothing.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from PaymentOrder o where o.providerOrderId = :providerOrderId")
  Optional<PaymentOrder> findByProviderOrderIdForUpdate(
      @Param("providerOrderId") String providerOrderId);

  /** Read-only lookup by provider order id - for status reads that must not take a lock. */
  Optional<PaymentOrder> findByProviderOrderId(String providerOrderId);

  /** The agreement's most recent order, whatever its status. Empty when payment never started. */
  Optional<PaymentOrder> findTopByAgreementIdOrderByCreatedAtDesc(UUID agreementId);

  /** How many orders this agreement has accumulated - drives the retry suffix on the receipt. */
  long countByAgreementId(UUID agreementId);

  /**
   * Outstanding orders older than {@code createdBefore}, oldest first - the reconciliation scan.
   * Bounded by the caller's {@link Pageable} so a backlog cannot become a stampede.
   */
  List<PaymentOrder> findByStatusAndCreatedAtLessThanOrderByCreatedAtAsc(
      PaymentOrderStatus status, Instant createdBefore, Pageable pageable);

  /**
   * Agreements with a non-surplus order confirmed paid at or after {@code cutoff} - the staff alert
   * look-back. The status is a literal, not a parameter, so the planner can use the partial index
   * on {@code confirmed_at} (V27).
   */
  @Query(
      "select distinct o.agreementId from PaymentOrder o"
          + " where o.status = in.agreementmitra.signing.payment.PaymentOrderStatus.PAID"
          + " and o.surplus = false"
          + " and o.confirmedAt >= :cutoff")
  List<UUID> findAgreementIdsPaidSince(@Param("cutoff") Instant cutoff);

  /**
   * Surplus orders confirmed paid at or after {@code cutoff} - the duplicate-payment look-back.
   * Disjoint from {@link #findAgreementIdsPaidSince} by the surplus predicate; same index.
   */
  @Query(
      "select new in.agreementmitra.signing.SurplusPayment(o.id, o.agreementId)"
          + " from PaymentOrder o"
          + " where o.status = in.agreementmitra.signing.payment.PaymentOrderStatus.PAID"
          + " and o.surplus = true"
          + " and o.confirmedAt >= :cutoff")
  List<SurplusPayment> findSurplusPaidSince(@Param("cutoff") Instant cutoff);

  /**
   * Orders that are not paid and have a ledger attempt, last received at or after {@code cutoff},
   * with one of {@code outcomes} - the unrecorded-payment look-back. Native, because the ledger has
   * no entity. The inner join drops an attempt for an order we do not hold.
   *
   * <p>An attempt that carried no payment id is left out: the confirmation checks the amount before
   * the missing id, so a gateway event without payment detail is recorded as an amount mismatch at
   * amount 0. It is not a captured payment. A real refusal always carries the id.
   *
   * <p>The aliases are quoted so Postgres keeps their case and they match the projection's getters.
   *
   * @param outcomes {@link PaymentEventOutcome#name()}s, bound by the caller so the list has one
   *     home
   */
  @Query(
      value =
          "SELECT DISTINCT o.id AS \"paymentOrderId\", o.agreement_id AS \"agreementId\","
              + " e.outcome AS \"outcome\""
              + " FROM payment_event e"
              + " JOIN payment_order o ON o.provider_order_id = e.provider_order_id"
              + " WHERE e.outcome IN (:outcomes)"
              + " AND e.provider_payment_id IS NOT NULL"
              + " AND e.last_received_at >= :cutoff"
              + " AND o.status <> 'PAID'",
      nativeQuery = true)
  List<UnrecordedAttempt> findUnrecordedSince(
      @Param("outcomes") Collection<String> outcomes, @Param("cutoff") Instant cutoff);

  /** One row of {@link #findUnrecordedSince}. */
  interface UnrecordedAttempt {

    UUID getPaymentOrderId();

    UUID getAgreementId();

    /** A {@link PaymentEventOutcome} name. */
    String getOutcome();
  }
}

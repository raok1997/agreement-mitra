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

  /** The columns of an order the staff orders view lists. No receipt: it is the agreement id. */
  String LISTED_ORDER_COLUMNS =
      "SELECT o.id AS \"paymentOrderId\", o.agreement_id AS \"agreementId\","
          + " o.provider_order_id AS \"providerOrderId\", o.status AS \"status\","
          + " o.amount_minor_units AS \"amountMinorUnits\", o.currency AS \"currency\","
          + " o.created_at AS \"createdAt\""
          + " FROM payment_order o";

  /**
   * What makes a ledger attempt a captured payment that was not recorded: one of the attention
   * outcomes, carrying a payment id. Shared by the two queries below so they cannot disagree; the
   * Java side of the same rule is {@link PaymentEventLedger.Attempt#isStuck}.
   */
  String STUCK_ATTEMPT = " e.outcome IN (:attention) AND e.provider_payment_id IS NOT NULL";

  /**
   * Orders of a listed status with a stuck attempt, most recent such attempt first - the staff
   * orders view's paid-but-stuck group. Driven from the ledger, the small side. No date limit.
   */
  @Query(
      value =
          LISTED_ORDER_COLUMNS
              + " JOIN (SELECT e.provider_order_id, max(e.last_received_at) AS latest"
              + " FROM payment_event e WHERE"
              + STUCK_ATTEMPT
              + " GROUP BY e.provider_order_id) s ON s.provider_order_id = o.provider_order_id"
              + " WHERE o.status IN (:listed)"
              + " ORDER BY s.latest DESC, o.id"
              + " LIMIT :limit",
      nativeQuery = true)
  List<ListedOrder> findStuck(
      @Param("attention") Collection<String> attention,
      @Param("listed") Collection<String> listed,
      @Param("limit") int limit);

  /**
   * Orders of a listed status created at or after {@code cutoff} with no stuck attempt, newest
   * first - the staff orders view's not-paid group.
   */
  @Query(
      value =
          LISTED_ORDER_COLUMNS
              + " WHERE o.status IN (:listed) AND o.created_at >= :cutoff"
              + " AND NOT EXISTS (SELECT 1 FROM payment_event e"
              + " WHERE e.provider_order_id = o.provider_order_id AND"
              + STUCK_ATTEMPT
              + ")"
              + " ORDER BY o.created_at DESC, o.id"
              + " LIMIT :limit",
      nativeQuery = true)
  List<ListedOrder> findNotPaid(
      @Param("attention") Collection<String> attention,
      @Param("listed") Collection<String> listed,
      @Param("cutoff") Instant cutoff,
      @Param("limit") int limit);

  /** One row of {@link #findStuck} or {@link #findNotPaid}. */
  interface ListedOrder {

    UUID getPaymentOrderId();

    UUID getAgreementId();

    String getProviderOrderId();

    /** A {@link PaymentOrderStatus} name. */
    String getStatus();

    long getAmountMinorUnits();

    String getCurrency();

    Instant getCreatedAt();
  }

  /**
   * Orders recorded under one of {@code paymentIds} - who holds a gateway payment id a later
   * attempt was refused for. By {@code uq_payment_order_provider_payment_id}.
   */
  @Query(
      value =
          "SELECT o.id AS \"paymentOrderId\", o.provider_payment_id AS \"providerPaymentId\","
              + " o.agreement_id AS \"agreementId\""
              + " FROM payment_order o"
              + " WHERE o.provider_payment_id IN (:paymentIds)",
      nativeQuery = true)
  List<PaymentHolder> findHolding(@Param("paymentIds") Collection<String> paymentIds);

  /** One row of {@link #findHolding}. */
  interface PaymentHolder {

    UUID getPaymentOrderId();

    String getProviderPaymentId();

    UUID getAgreementId();
  }
}

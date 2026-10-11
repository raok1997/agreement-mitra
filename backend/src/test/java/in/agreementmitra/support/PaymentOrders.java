package in.agreementmitra.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a gateway payment order directly, for a test whose subject is what happens <b>after</b>
 * an order reaches a status, not how it got there. The confirmation path itself is exercised
 * through the real API in the Razorpay tests.
 *
 * <p>At most one {@code CREATED} order per agreement ({@code uq_payment_order_open_per_agreement}).
 */
public final class PaymentOrders {

  private PaymentOrders() {}

  /**
   * @param status {@code CREATED}, {@code PAID}, {@code FAILED} or {@code EXPIRED}
   * @param confirmedAt when the order was confirmed paid; {@code null} for an unpaid order
   * @return the new order's id
   */
  public static UUID insert(
      JdbcTemplate jdbc, UUID agreementId, String status, Instant confirmedAt) {
    return insert(jdbc, agreementId, status, confirmedAt, false);
  }

  /**
   * @param surplus whether the order is marked surplus - a payment captured on an agreement that
   *     already held one; only meaningful with {@code PAID}
   * @return the new order's id
   */
  public static UUID insert(
      JdbcTemplate jdbc, UUID agreementId, String status, Instant confirmedAt, boolean surplus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,"
            + " amount_minor_units, currency, status, created_at, confirmed_at, surplus)"
            + " VALUES (?, ?, 'razorpay', ?, ?, 49900, 'INR', ?, ?, ?, ?)",
        id,
        agreementId,
        providerOrderId(id),
        id.toString(),
        status,
        Timestamp.from(Instant.now()),
        confirmedAt == null ? null : Timestamp.from(confirmedAt),
        surplus);
    return id;
  }

  /**
   * A gateway payment id one character longer than {@code payment_order.provider_payment_id} holds.
   * Postgres refuses the order write with SQL state 22001, which names no constraint: a real
   * refusal that is not a duplicate, with no test-only seam. It fits {@code
   * agreement.payment_reference}, so the order write is the only one the database refuses.
   *
   * <p>If the column is widened or the id is validated earlier, the tests using this must pick
   * another provocation. Each one therefore asserts the refusal itself - the failure, the {@code
   * 500}, or the ERROR line - and not only the unchanged state it leaves.
   */
  public static String overLongPaymentId() {
    return ("pay_TOOLONG" + UUID.randomUUID().toString().replace("-", "") + "X".repeat(65))
        .substring(0, 65);
  }

  /** The provider order id {@link #insert} gives the order with this id. */
  public static String providerOrderId(UUID id) {
    return "order_" + id;
  }
}

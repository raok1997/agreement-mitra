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
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,"
            + " amount_minor_units, currency, status, created_at, confirmed_at)"
            + " VALUES (?, ?, 'razorpay', ?, ?, 49900, 'INR', ?, ?, ?)",
        id,
        agreementId,
        "order_" + id,
        id.toString(),
        status,
        Timestamp.from(Instant.now()),
        confirmedAt == null ? null : Timestamp.from(confirmedAt));
    return id;
  }
}

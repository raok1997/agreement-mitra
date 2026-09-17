package in.agreementmitra.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixture for a <b>paid order with a frozen stamp quote</b> (state-stamp-duty-quoting), for tests
 * whose subject is what happens after payment -- stamp intake, the staff queue -- rather than the
 * checkout that normally writes these rows. Inserts the payment order and its stamp quote directly,
 * then marks the agreement paid, so no payment provider is involved.
 */
public final class StampQuotes {

  private StampQuotes() {}

  /**
   * Freeze a paid order.
   *
   * @param dutyMinorUnits the legal duty the quote recorded
   * @param stampValueMinorUnits the stamp value the customer chose; below the duty means an
   *     acknowledged below-duty choice
   */
  public static void freezePaid(
      JdbcTemplate jdbc, UUID agreementId, long dutyMinorUnits, long stampValueMinorUnits) {
    UUID orderId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,"
            + " amount_minor_units, currency, status, created_at, confirmed_at, version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,0)",
        orderId,
        agreementId,
        "razorpay",
        "order_" + orderId.toString().replace("-", "").substring(0, 18),
        agreementId.toString(),
        49_900L + Math.max(0L, stampValueMinorUnits - 10_000L),
        "INR",
        "PAID",
        now,
        now);
    boolean below = stampValueMinorUnits < dutyMinorUnits;
    jdbc.update(
        "INSERT INTO stamp_quote (payment_order_id, agreement_id, duty_minor_units,"
            + " stamp_value_minor_units, below_duty, medium_id, rule_id, rule_content_hash,"
            + " rule_reviewed, catalog_content_hash, execution_date, registration_required,"
            + " breakdown, ack_warning_version, ack_identity_id, ack_at, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?)",
        orderId,
        agreementId,
        dutyMinorUnits,
        stampValueMinorUnits,
        below,
        below ? "stamp-paper" : "challan",
        "TG-lease-residential",
        "a".repeat(64),
        false,
        "b".repeat(64),
        java.sql.Date.valueOf("2026-01-01"),
        true,
        "[]",
        below ? "under-stamp-v1" : null,
        null,
        below ? now : null,
        now);
    Payments.markPaid(jdbc, agreementId, "pay_" + orderId.toString().substring(0, 8));
  }
}

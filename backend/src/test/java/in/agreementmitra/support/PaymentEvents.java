package in.agreementmitra.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a payment event ledger row directly, for a test whose subject is what <b>reads</b> the
 * ledger, not how an attempt comes to be recorded. The ledger's own writer is exercised through the
 * real confirmation path in the payment tests.
 */
public final class PaymentEvents {

  private PaymentEvents() {}

  /**
   * One distinct attempt, received once.
   *
   * @param source {@code WEBHOOK}, {@code BROWSER_CALLBACK} or {@code RECONCILIATION}
   * @param outcome a ledger outcome name, e.g. {@code RECORDING_FAILED}
   * @return the gateway payment id the row carries
   */
  public static String insert(
      JdbcTemplate jdbc,
      String providerOrderId,
      String source,
      String outcome,
      Instant firstReceivedAt,
      Instant lastReceivedAt) {
    String providerPaymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
    insert(
        jdbc,
        providerOrderId,
        providerPaymentId,
        49900,
        source,
        outcome,
        firstReceivedAt,
        lastReceivedAt);
    return providerPaymentId;
  }

  /**
   * As above, with the payment id and amount the attempt carried.
   *
   * @param providerPaymentId {@code null} for an event that carried no payment detail
   */
  public static void insert(
      JdbcTemplate jdbc,
      String providerOrderId,
      String providerPaymentId,
      long amountMinorUnits,
      String source,
      String outcome,
      Instant firstReceivedAt,
      Instant lastReceivedAt) {
    insert(
        jdbc,
        providerOrderId,
        providerPaymentId,
        amountMinorUnits,
        "INR",
        source,
        outcome,
        null,
        null,
        firstReceivedAt,
        lastReceivedAt,
        1);
  }

  /**
   * Every column a reader of the ledger reports.
   *
   * @param providerPaymentId {@code null} for an event that carried no payment detail
   * @param refusedRule the constraint that refused the write, or {@code null}
   * @param sqlState the SQL state of the refusal, or {@code null}
   * @param attemptCount how many times the attempt was received
   */
  public static void insert(
      JdbcTemplate jdbc,
      String providerOrderId,
      String providerPaymentId,
      long amountMinorUnits,
      String currency,
      String source,
      String outcome,
      String refusedRule,
      String sqlState,
      Instant firstReceivedAt,
      Instant lastReceivedAt,
      long attemptCount) {
    jdbc.update(
        "INSERT INTO payment_event (attempt_key, source, provider_order_id, provider_payment_id,"
            + " amount_minor_units, currency, outcome, refused_rule, sql_state,"
            + " first_received_at, last_received_at, attempt_count)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        uniqueAttemptKey(),
        source,
        providerOrderId,
        providerPaymentId,
        amountMinorUnits,
        currency,
        outcome,
        refusedRule,
        sqlState,
        Timestamp.from(firstReceivedAt),
        Timestamp.from(lastReceivedAt),
        attemptCount);
  }

  /** The same attempt received again at {@code receivedAt}: every row for the order is repeated. */
  public static void receiveAgain(JdbcTemplate jdbc, String providerOrderId, Instant receivedAt) {
    jdbc.update(
        "UPDATE payment_event SET attempt_count = attempt_count + 1, last_received_at = ?"
            + " WHERE provider_order_id = ?",
        Timestamp.from(receivedAt),
        providerOrderId);
  }

  /** 64 characters, as {@code attempt_key CHAR(64)} needs, and unique. */
  private static String uniqueAttemptKey() {
    return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
  }
}

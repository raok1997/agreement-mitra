package in.agreementmitra.signing.payment;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the payment event ledger: one row per distinct gateway confirmation attempt, and a count
 * of its repeats (payment-event-ledger D2, D9). The only class that writes {@code payment_event}.
 *
 * <p><b>It cannot fail its caller, and this is the one place that guarantee lives.</b> The attempts
 * most worth recording are the ones whose confirmation rolled back, so the row is written in its
 * own transaction; and a ledger that cannot be written must not turn a confirmed payment into an
 * error, nor a refused one into an acknowledgement. The catch therefore encloses the whole template
 * call, commit included, not just the statement.
 *
 * <p>The write is best-effort: a row can be lost, so the ledger is not guaranteed complete.
 */
@Component
class PaymentEventLedger {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventLedger.class);

  /**
   * A repeat only raises the count and widens the two times. LEAST and GREATEST because attempts do
   * not reach the ledger in the order they were received.
   */
  private static final String UPSERT =
      """
      INSERT INTO payment_event (
          attempt_key, source, presented_event_id, event_type, provider_order_id,
          provider_payment_id, amount_minor_units, currency, outcome, refused_rule, sql_state,
          first_received_at, last_received_at, attempt_count)
      VALUES (
          :attemptKey, :source, :presentedEventId, :eventType, :providerOrderId,
          :providerPaymentId, :amountMinorUnits, :currency, :outcome, :refusedRule, :sqlState,
          :receivedAt, :receivedAt, 1)
      ON CONFLICT (attempt_key) DO UPDATE
         SET attempt_count     = payment_event.attempt_count + 1,
             first_received_at = LEAST(payment_event.first_received_at, EXCLUDED.first_received_at),
             last_received_at  = GREATEST(payment_event.last_received_at, EXCLUDED.last_received_at)
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate ownTransaction;

  PaymentEventLedger(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Record one attempt. Never throws: a failure is logged and the attempt's result stands. */
  void record(
      PaymentEventOrigin origin,
      String providerOrderId,
      String providerPaymentId,
      long amountMinorUnits,
      String currency,
      Instant receivedAt,
      PaymentEventOutcome outcome,
      String refusedRule,
      String sqlState) {
    try {
      PaymentEvent event =
          PaymentEvent.of(
              origin,
              providerOrderId,
              providerPaymentId,
              amountMinorUnits,
              currency,
              receivedAt,
              outcome,
              refusedRule,
              sqlState);
      ownTransaction.executeWithoutResult(status -> write(event));
    } catch (RuntimeException e) {
      // Source and outcome only: no order id, no payment id, no amount, and not the exception,
      // whose message is the SQL and the row.
      log.warn(
          "Payment event could not be recorded (source {}, outcome {})",
          origin == null ? null : origin.source(),
          outcome);
    }
  }

  private void write(PaymentEvent event) {
    jdbc.sql(UPSERT)
        .param("attemptKey", event.attemptKey())
        .param("source", event.source())
        .param("presentedEventId", event.presentedEventId(), Types.VARCHAR)
        .param("eventType", event.eventType(), Types.VARCHAR)
        .param("providerOrderId", event.providerOrderId())
        .param("providerPaymentId", event.providerPaymentId(), Types.VARCHAR)
        .param("amountMinorUnits", event.amountMinorUnits())
        .param("currency", event.currency(), Types.VARCHAR)
        .param("outcome", event.outcome())
        .param("refusedRule", event.refusedRule(), Types.VARCHAR)
        .param("sqlState", event.sqlState(), Types.VARCHAR)
        // An Instant cannot be bound; the driver takes an OffsetDateTime for TIMESTAMPTZ.
        .param("receivedAt", OffsetDateTime.ofInstant(event.receivedAt(), ZoneOffset.UTC))
        .update();
  }
}

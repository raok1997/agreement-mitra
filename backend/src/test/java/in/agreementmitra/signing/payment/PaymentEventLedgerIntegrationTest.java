package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.payment.PaymentEventOrigin.Source;
import in.agreementmitra.support.HarnessTestConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The ledger's one statement against real Postgres.
 *
 * <p>The ledger swallows every failure by design, so a statement that could not run would show up
 * only as a WARN. Every case here therefore reads the row back: a write that did not happen fails
 * the test.
 *
 * <p>Declared exactly as the plain integration classes are (no property source, no bean override),
 * so it reuses their cached Spring context instead of starting one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentEventLedgerIntegrationTest {

  /** Postgres keeps microseconds; a received time finer than that would not read back equal. */
  private static final Instant RECEIVED = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private PaymentEventLedger ledger;
  @Autowired private JdbcTemplate jdbc;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID();
  }

  private List<Map<String, Object>> paymentEvents(String providerOrderId) {
    return jdbc.queryForList(
        "SELECT * FROM payment_event WHERE provider_order_id = ? ORDER BY id", providerOrderId);
  }

  private static Instant instant(Map<String, Object> row, String column) {
    return ((java.sql.Timestamp) row.get(column)).toInstant();
  }

  private static PaymentEventOrigin originOf(Source source) {
    return switch (source) {
      case WEBHOOK -> PaymentEventOrigin.webhook("evt_1", "payment.captured");
      case BROWSER_CALLBACK -> PaymentEventOrigin.browserCallback();
      case RECONCILIATION -> PaymentEventOrigin.reconciliation();
    };
  }

  /** The enums are the one home for these lists; this is what pins that the columns hold them. */
  @Test
  void everySourceAndEveryOutcomeIsStoredAndReadBackByName() {
    String order = unique("order_");

    for (Source source : Source.values()) {
      for (PaymentEventOutcome outcome : PaymentEventOutcome.values()) {
        ledger.record(
            originOf(source), order, "pay_1", 49_900L, "INR", RECEIVED, outcome, null, null);
      }
    }

    List<Map<String, Object>> rows = paymentEvents(order);
    assertThat(rows).hasSize(Source.values().length * PaymentEventOutcome.values().length);
    assertThat(rows)
        .extracting(row -> row.get("source") + "/" + row.get("outcome"))
        .contains(
            "WEBHOOK/ERRORED", "RECONCILIATION/MISSING_REFERENCE", "BROWSER_CALLBACK/CONFIRMED")
        .doesNotHaveDuplicates();
    assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("attempt_count", 1L));
  }

  @Test
  void aRepeatReceivedOutOfOrderMovesFirstReceivedBackAndLeavesLastReceived() {
    String order = unique("order_");
    Instant earlier = RECEIVED.minusSeconds(60);
    PaymentEventOrigin origin = PaymentEventOrigin.reconciliation();

    ledger.record(
        origin,
        order,
        "pay_1",
        1L,
        "INR",
        RECEIVED,
        PaymentEventOutcome.RECORDING_FAILED,
        null,
        "22001");
    ledger.record(
        origin,
        order,
        "pay_1",
        1L,
        "INR",
        earlier,
        PaymentEventOutcome.RECORDING_FAILED,
        null,
        "22001");

    assertThat(paymentEvents(order))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("attempt_count", 2L);
              assertThat(instant(row, "first_received_at")).isEqualTo(earlier);
              assertThat(instant(row, "last_received_at")).isEqualTo(RECEIVED);
            });
  }

  @Test
  void aRepeatReceivedLaterMovesLastReceivedForwardAndLeavesFirstReceived() {
    String order = unique("order_");
    Instant later = RECEIVED.plusSeconds(60);
    PaymentEventOrigin origin = PaymentEventOrigin.reconciliation();

    ledger.record(
        origin, order, "pay_1", 1L, "INR", RECEIVED, PaymentEventOutcome.ERRORED, null, null);
    ledger.record(
        origin, order, "pay_1", 1L, "INR", later, PaymentEventOutcome.ERRORED, null, null);

    assertThat(paymentEvents(order))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row)
                  .containsEntry("outcome", "ERRORED")
                  .containsEntry("refused_rule", null)
                  .containsEntry("sql_state", null)
                  .containsEntry("attempt_count", 2L);
              assertThat(instant(row, "first_received_at")).isEqualTo(RECEIVED);
              assertThat(instant(row, "last_received_at")).isEqualTo(later);
            });
  }

  /** The values that broke the payment write must not break this one. */
  @Test
  void aValueHoldingANulAndAnOverLongOrderIdAreStored() {
    String prefix = unique("order_");
    String overLong = prefix + "x".repeat(300 - prefix.length());

    ledger.record(
        PaymentEventOrigin.webhook(null, "payment.captured"),
        overLong,
        "pay\u0000_nul",
        49_900L,
        "INR",
        RECEIVED,
        PaymentEventOutcome.RECORDING_FAILED,
        null,
        "22021");

    assertThat(paymentEvents(overLong.substring(0, PaymentEvent.PROVIDER_ID_WIDTH)))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("provider_payment_id", "pay_nul"));
  }

  @Test
  void anErroredAttemptKeepsItsSqlStateAndAnAbsentPaymentIdStaysAbsent() {
    String order = unique("order_");

    ledger.record(
        PaymentEventOrigin.reconciliation(),
        order,
        null,
        0L,
        null,
        RECEIVED,
        PaymentEventOutcome.ERRORED,
        null,
        "40001");

    assertThat(paymentEvents(order))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("outcome", "ERRORED")
                    .containsEntry("sql_state", "40001")
                    .containsEntry("refused_rule", null)
                    .containsEntry("provider_payment_id", null)
                    .containsEntry("currency", null)
                    .containsEntry("amount_minor_units", 0L));
  }
}

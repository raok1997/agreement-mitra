package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What the ledger stores for an attempt, and which attempts it treats as the same one. */
class PaymentEventTest {

  private static final Instant RECEIVED = Instant.parse("2026-10-11T05:00:00Z");
  private static final PaymentEventOrigin WEBHOOK =
      PaymentEventOrigin.webhook("evt_1", "payment.captured");

  private static PaymentEvent event(
      PaymentEventOrigin origin,
      String orderId,
      String paymentId,
      long amount,
      String currency,
      PaymentEventOutcome outcome,
      String rule,
      String sqlState) {
    return PaymentEvent.of(
        origin, orderId, paymentId, amount, currency, RECEIVED, outcome, rule, sqlState);
  }

  private static PaymentEvent baseline() {
    return event(
        WEBHOOK, "order_1", "pay_1", 49_900L, "INR", PaymentEventOutcome.CONFIRMED, null, null);
  }

  // --- storable values ---------------------------------------------------------

  @Test
  void overLongValuesAreCutToTheirColumnWidths() {
    PaymentEvent event =
        event(
            WEBHOOK,
            "o".repeat(300),
            "p".repeat(300),
            1L,
            "C".repeat(20),
            PaymentEventOutcome.RECORDING_FAILED,
            "r".repeat(200),
            "S".repeat(12));

    assertThat(event.providerOrderId()).isEqualTo("o".repeat(255));
    assertThat(event.providerPaymentId()).isEqualTo("p".repeat(255));
    assertThat(event.currency()).isEqualTo("C".repeat(16));
    assertThat(event.refusedRule()).isEqualTo("r".repeat(128));
    assertThat(event.sqlState()).isEqualTo("S".repeat(8));
  }

  @Test
  void aValueTheOrderColumnCannotHoldIsKeptWhole() {
    String sixtyFive = "p".repeat(65);

    assertThat(
            event(
                    WEBHOOK,
                    "order_1",
                    sixtyFive,
                    1L,
                    "INR",
                    PaymentEventOutcome.RECORDING_FAILED,
                    null,
                    "22001")
                .providerPaymentId())
        .isEqualTo(sixtyFive);
  }

  @Test
  void absentValuesStayAbsent() {
    PaymentEvent event =
        event(
            PaymentEventOrigin.reconciliation(),
            "order_1",
            null,
            0L,
            null,
            PaymentEventOutcome.ERRORED,
            null,
            null);

    assertThat(event.presentedEventId()).isNull();
    assertThat(event.eventType()).isNull();
    assertThat(event.providerPaymentId()).isNull();
    assertThat(event.currency()).isNull();
    assertThat(event.refusedRule()).isNull();
    assertThat(event.sqlState()).isNull();
  }

  @Test
  void theTwoCharactersAStoredValueMayNotHoldAreRemoved() {
    PaymentEvent event =
        event(
            WEBHOOK,
            "order\u0000_\u001F1",
            "pay\u0000_1",
            1L,
            "INR",
            PaymentEventOutcome.CONFIRMED,
            null,
            null);

    assertThat(event.providerOrderId()).isEqualTo("order_1");
    assertThat(event.providerPaymentId()).isEqualTo("pay_1");
  }

  @Test
  void aCutNeverLeavesHalfASurrogatePair() {
    // The pair straddles the cut: 254 characters, then a two-char code point.
    String value = "o".repeat(254) + "😀";

    String stored =
        event(WEBHOOK, value, null, 1L, "INR", PaymentEventOutcome.CONFIRMED, null, null)
            .providerOrderId();

    assertThat(stored).isEqualTo("o".repeat(254));
  }

  // --- the attempt key ---------------------------------------------------------

  @Test
  void theKeyIgnoresTheReceivedTimeAndThePresentedEventId() {
    PaymentEvent later =
        PaymentEvent.of(
            PaymentEventOrigin.webhook("evt_another", "payment.captured"),
            "order_1",
            "pay_1",
            49_900L,
            "INR",
            RECEIVED.plusSeconds(600),
            PaymentEventOutcome.CONFIRMED,
            null,
            null);

    assertThat(later.attemptKey()).isEqualTo(baseline().attemptKey());
  }

  @Test
  void theKeyDiffersWhenAnyOneOfTheNineValuesDiffers() {
    PaymentEventOutcome confirmed = PaymentEventOutcome.CONFIRMED;
    Set<String> keys = new LinkedHashSet<>();
    keys.add(baseline().attemptKey());
    keys.add(
        event(WEBHOOK, "order_2", "pay_1", 49_900L, "INR", confirmed, null, null).attemptKey());
    keys.add(
        event(
                PaymentEventOrigin.reconciliation(),
                "order_1",
                "pay_1",
                49_900L,
                "INR",
                confirmed,
                null,
                null)
            .attemptKey());
    keys.add(
        event(
                PaymentEventOrigin.webhook("evt_1", "order.paid"),
                "order_1",
                "pay_1",
                49_900L,
                "INR",
                confirmed,
                null,
                null)
            .attemptKey());
    keys.add(
        event(
                WEBHOOK,
                "order_1",
                "pay_1",
                49_900L,
                "INR",
                PaymentEventOutcome.ALREADY_CONFIRMED,
                null,
                null)
            .attemptKey());
    keys.add(
        event(WEBHOOK, "order_1", "pay_2", 49_900L, "INR", confirmed, null, null).attemptKey());
    keys.add(
        event(WEBHOOK, "order_1", "pay_1", 49_901L, "INR", confirmed, null, null).attemptKey());
    keys.add(
        event(WEBHOOK, "order_1", "pay_1", 49_900L, "USD", confirmed, null, null).attemptKey());
    keys.add(
        event(WEBHOOK, "order_1", "pay_1", 49_900L, "INR", confirmed, "uq_rule", null)
            .attemptKey());
    keys.add(
        event(WEBHOOK, "order_1", "pay_1", 49_900L, "INR", confirmed, null, "23505").attemptKey());

    assertThat(keys).hasSize(10);
  }

  @Test
  void adjacentValuesCannotRunTogether() {
    PaymentEventOutcome confirmed = PaymentEventOutcome.CONFIRMED;

    assertThat(event(WEBHOOK, "o", "ab", 1L, "c", confirmed, null, null).attemptKey())
        .isNotEqualTo(event(WEBHOOK, "o", "a", 1L, "bc", confirmed, null, null).attemptKey());
    assertThat(event(WEBHOOK, "o", "p", 1L, "INR", confirmed, "ab", "c").attemptKey())
        .isNotEqualTo(event(WEBHOOK, "o", "p", 1L, "INR", confirmed, "a", "bc").attemptKey());
  }

  @Test
  void absentEmptyAndTheWordNullAreThreeDifferentValues() {
    PaymentEventOutcome confirmed = PaymentEventOutcome.CONFIRMED;
    Set<String> keys = new LinkedHashSet<>();
    for (String paymentId : new String[] {null, "", "null"}) {
      keys.add(event(WEBHOOK, "o", paymentId, 1L, "INR", confirmed, null, null).attemptKey());
    }

    assertThat(keys).hasSize(3);
  }

  /**
   * A known answer: SHA-256 over {@code order_1, WEBHOOK, payment.captured, CONFIRMED, pay_1,
   * 49900, INR, <absent>, <absent>} joined by U+001F, an absent value being one NUL. A change to
   * the encoding gives every identical attempt a second row, so it must fail a test first.
   */
  @Test
  void theKeyEncodingIsFixed() {
    assertThat(baseline().attemptKey())
        .isEqualTo("5c64af252763ac4105b4052fcd029941f460b92a42675867bd1a878f1b79a530");
  }
}

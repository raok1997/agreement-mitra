package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.payment.PaymentEventOrigin.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The presented event id is outside the webhook signature: it is kept only as a plain label. */
class PaymentEventOriginTest {

  @Test
  void aPlainEventIdIsKept() {
    PaymentEventOrigin origin = PaymentEventOrigin.webhook("evt_Rzp-9f3K1", "payment.captured");

    assertThat(origin.source()).isEqualTo(Source.WEBHOOK);
    assertThat(origin.presentedEventId()).isEqualTo("evt_Rzp-9f3K1");
    assertThat(origin.eventType()).isEqualTo("payment.captured");
  }

  @Test
  void anEventIdOfExactlyTheLimitIsKept() {
    String atLimit = "e".repeat(64);

    assertThat(PaymentEventOrigin.webhook(atLimit, "order.paid").presentedEventId())
        .isEqualTo(atLimit);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "evt 1", "evt/1", "evt\n1", "evt_1\n"})
  void anEventIdThatIsNotAPlainTokenIsStoredAsAbsent(String presented) {
    PaymentEventOrigin origin = PaymentEventOrigin.webhook(presented, "payment.captured");

    assertThat(origin.presentedEventId()).isNull();
    assertThat(origin.eventType()).isEqualTo("payment.captured");
  }

  @Test
  void anEventIdOverTheLimitIsStoredAsAbsent() {
    assertThat(PaymentEventOrigin.webhook("e".repeat(65), "payment.captured").presentedEventId())
        .isNull();
  }

  @Test
  void theCallbackAndReconciliationOriginsCarryNoEventIdAndNoEventType() {
    assertThat(PaymentEventOrigin.browserCallback())
        .isEqualTo(new PaymentEventOrigin(Source.BROWSER_CALLBACK, null, null));
    assertThat(PaymentEventOrigin.reconciliation())
        .isEqualTo(new PaymentEventOrigin(Source.RECONCILIATION, null, null));
  }
}

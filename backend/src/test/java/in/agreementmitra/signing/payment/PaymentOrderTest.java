package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The surplus mark is set once, when the order is marked paid, and never changes afterwards. */
class PaymentOrderTest {

  private static PaymentOrder anOrder() {
    UUID agreementId = UUID.randomUUID();
    return PaymentOrder.create(
        agreementId,
        "order_UNIT",
        agreementId.toString(),
        new Money(49_900L, "INR"),
        Instant.now());
  }

  @Test
  void aNewOrderIsNotSurplus() {
    assertThat(anOrder().surplus()).isFalse();
  }

  @Test
  void markPaidStoresTheSurplusMark() {
    PaymentOrder surplus = anOrder();
    PaymentOrder credited = anOrder();

    surplus.markPaid("pay_A", Instant.now(), true);
    credited.markPaid("pay_B", Instant.now(), false);

    assertThat(surplus.status()).isEqualTo(PaymentOrderStatus.PAID);
    assertThat(surplus.surplus()).isTrue();
    assertThat(credited.surplus()).isFalse();
  }

  @Test
  void aSecondMarkPaidChangesNeitherTheMarkNorThePaymentId() {
    PaymentOrder surplus = anOrder();
    Instant first = Instant.now();
    surplus.markPaid("pay_A", first, true);
    PaymentOrder credited = anOrder();
    credited.markPaid("pay_B", first, false);

    surplus.markPaid("pay_OTHER", first.plusSeconds(60), false);
    credited.markPaid("pay_OTHER", first.plusSeconds(60), true);

    assertThat(surplus.surplus()).isTrue();
    assertThat(surplus.providerPaymentId()).isEqualTo("pay_A");
    assertThat(surplus.confirmedAt()).isEqualTo(first);
    assertThat(credited.surplus()).isFalse();
    assertThat(credited.providerPaymentId()).isEqualTo("pay_B");
  }
}

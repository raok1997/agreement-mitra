package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.signing.PaymentGateMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pricing and the money representation.
 *
 * <p>Two things are pinned here. First, that the amount is a <b>configured</b> value reached
 * through a named operation, so introducing stamp duty later changes the calculation and nothing
 * else. Second, that money is <b>integer minor units</b> everywhere - the reflective check at the
 * bottom is deliberately blunt, because a {@code double} slipping into a monetary field is the kind
 * of mistake that produces off-by-one-paise failures nobody can reproduce.
 */
class PaymentPricingTest {

  private static PaymentPricing pricingAt(long minorUnits, String currency) {
    return new PaymentPricing(
        new PaymentProperties(
            PaymentGateMode.OPTIONAL,
            new PaymentProperties.Amount(minorUnits, currency),
            null,
            null));
  }

  @Test
  void pricingReturnsTheConfiguredMinorUnitsAndCurrency() {
    Money price = pricingAt(49_900L, "INR").priceFor(UUID.randomUUID());

    assertThat(price.minorUnits()).isEqualTo(49_900L);
    assertThat(price.currency()).isEqualTo("INR");
  }

  @Test
  void changingTheConfiguredPriceChangesTheAmountChargedWithNoOtherChange() {
    // The whole point of the seam: the price is data, not code.
    assertThat(pricingAt(120_000L, "INR").priceFor(UUID.randomUUID()).minorUnits())
        .isEqualTo(120_000L);
    assertThat(pricingAt(1L, "INR").priceFor(UUID.randomUUID()).minorUnits()).isEqualTo(1L);
  }

  @Test
  void anUnsetPriceFallsBackToTheSandboxDefaultRatherThanZero() {
    // A zero or negative price would place an order the provider rejects, at the worst possible
    // moment. Fall back to a sane positive value instead.
    Money price = pricingAt(0L, null).priceFor(UUID.randomUUID());

    assertThat(price.minorUnits()).isPositive();
    assertThat(price.currency()).isEqualTo("INR");
  }

  @Test
  void currencyIsNormalisedToUpperCase() {
    assertThat(pricingAt(500L, "inr").priceFor(UUID.randomUUID()).currency()).isEqualTo("INR");
  }

  // --- money representation --------------------------------------------------

  @Test
  void minorUnitsConvertToMajorUnitsExactlyAndWithoutDivision() {
    // 49900 paise is 499.00 rupees exactly - a scale change, not a division, so nothing is rounded
    // and nothing is lost on the way to the vendor-neutral confirmation seam.
    assertThat(new Money(49_900L, "INR").toMajorUnits())
        .isEqualByComparingTo(new BigDecimal("499.00"));
    assertThat(new Money(1L, "INR").toMajorUnits()).isEqualByComparingTo(new BigDecimal("0.01"));
    assertThat(new Money(1L, "INR").toMajorUnits().scale()).isEqualTo(2);
  }

  @Test
  void anAmountMustBePositiveAndCarryACurrency() {
    assertThatThrownBy(() -> new Money(0L, "INR")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Money(-1L, "INR")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Money(100L, " ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theAmountCrossCheckIsExactOnBothAmountAndCurrency() {
    Money order = new Money(49_900L, "INR");

    assertThat(order.matches(49_900L, "INR")).isTrue();
    assertThat(order.matches(49_900L, "inr")).isTrue(); // casing only
    assertThat(order.matches(49_899L, "INR")).isFalse(); // one paise short is a mismatch
    assertThat(order.matches(49_900L, "USD")).isFalse();
    assertThat(order.matches(49_900L, null)).isFalse();
  }

  @Test
  void noFloatingPointTypeAppearsAnywhereInTheMonetaryPath() {
    // Blunt on purpose. Razorpay requires paise and rejects floats; more importantly,
    // floating-point money produces off-by-one-paise errors that fail the amount-equality check a
    // confirmation depends on. Deciding this once, at the boundary, avoids it everywhere - so a
    // future edit that reintroduces a double here fails loudly rather than quietly.
    assertThat(monetaryTypesOf(Money.class)).allMatch(PaymentPricingTest::isNotFloatingPoint);
    for (Field field : PaymentOrder.class.getDeclaredFields()) {
      if (field.getName().toLowerCase().contains("amount")) {
        assertThat(isNotFloatingPoint(field.getType()))
            .as("PaymentOrder.%s must not be floating point", field.getName())
            .isTrue();
      }
    }
    for (Method method : Money.class.getDeclaredMethods()) {
      assertThat(isNotFloatingPoint(method.getReturnType()))
          .as("Money.%s must not return a floating-point type", method.getName())
          .isTrue();
    }
  }

  private static Class<?>[] monetaryTypesOf(Class<?> record) {
    RecordComponent[] components = record.getRecordComponents();
    Class<?>[] types = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
    }
    return types;
  }

  private static boolean isNotFloatingPoint(Class<?> type) {
    return type != double.class
        && type != float.class
        && type != Double.class
        && type != Float.class;
  }
}

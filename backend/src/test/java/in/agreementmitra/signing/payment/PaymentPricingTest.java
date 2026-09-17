package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.signing.PaymentGateMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pricing and the money representation.
 *
 * <p>Two things are pinned here. First, that the amount follows the <b>published pricing rule</b>
 * (base fee plus the stamp value above the included amount) from configured figures. Second, that
 * money is <b>integer minor units</b> everywhere - the reflective check at the bottom is
 * deliberately blunt, because a {@code double} slipping into a monetary field is the kind of
 * mistake that produces off-by-one-paise failures nobody can reproduce.
 */
class PaymentPricingTest {

  private static PaymentPricing pricing(Long base, Long included, String currency) {
    return new PaymentPricing(
        new PaymentProperties(
            PaymentGateMode.OPTIONAL,
            new PaymentProperties.Fee(base, included, currency),
            null,
            null));
  }

  private static final PaymentPricing PUBLISHED = pricing(49_900L, 10_000L, "INR");

  @Test
  void stampValueWithinTheIncludedAmountCostsTheBaseFee() {
    // TERMS-OF-SERVICE section 7: INR 499 where the stamp value is INR 100 or less.
    assertThat(PUBLISHED.price(10_000L).minorUnits()).isEqualTo(49_900L);
    assertThat(PUBLISHED.price(4_000L).minorUnits()).isEqualTo(49_900L);
    assertThat(PUBLISHED.price(0L).minorUnits()).isEqualTo(49_900L);
    assertThat(PUBLISHED.price(10_000L).currency()).isEqualTo("INR");
  }

  @Test
  void stampValueAboveTheIncludedAmountAddsTheExcess() {
    // INR 840 stamp value: 499 + (840 - 100) = INR 1,239.
    assertThat(PUBLISHED.price(84_000L).minorUnits()).isEqualTo(123_900L);
    assertThat(PUBLISHED.price(10_001L).minorUnits()).isEqualTo(49_901L);
  }

  @Test
  void theRuleIsConfigurationNotCode() {
    assertThat(pricing(60_000L, 0L, "INR").price(10_000L).minorUnits()).isEqualTo(70_000L);
  }

  @Test
  void unsetFiguresFallBackToThePublishedRuleRatherThanZero() {
    // A zero or negative base would place an order the provider rejects, at the worst moment.
    PaymentPricing unset = pricing(0L, null, null);

    assertThat(unset.price(84_000L).minorUnits()).isEqualTo(123_900L);
    assertThat(unset.price(84_000L).currency()).isEqualTo("INR");
  }

  @Test
  void aNegativeStampValueIsRefused() {
    assertThatThrownBy(() -> PUBLISHED.price(-1L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void currencyIsNormalisedToUpperCase() {
    assertThat(pricing(500L, 0L, "inr").price(0L).currency()).isEqualTo("INR");
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

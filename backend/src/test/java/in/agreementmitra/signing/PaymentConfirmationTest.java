package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The one normal form of a payment reference, shared by the manual path and the gateway check. */
class PaymentConfirmationTest {

  @ParameterizedTest
  @CsvSource({
    "pay_AbC123, PAY_ABC123",
    "'  pay_AbC123  ', PAY_ABC123",
    "'neft   2026 	 77', NEFT 2026 77",
    "PAY_ABC123, PAY_ABC123"
  })
  void caseAndWhitespaceDoNotDistinguishTwoReferences(String raw, String normal) {
    assertThat(PaymentConfirmation.normalizeReference(raw)).isEqualTo(normal);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "\t\n"})
  void aReferenceWithNothingInItIsNoReference(String raw) {
    assertThat(PaymentConfirmation.normalizeReference(raw)).isNull();
  }

  /** Under a Turkish default locale a bare toUpperCase() turns "i" into a dotted capital. */
  @Test
  void upperCasingDoesNotDependOnTheDefaultLocale() {
    Locale original = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      assertThat(PaymentConfirmation.normalizeReference("pay_iii")).isEqualTo("PAY_III");
    } finally {
      Locale.setDefault(original);
    }
  }
}

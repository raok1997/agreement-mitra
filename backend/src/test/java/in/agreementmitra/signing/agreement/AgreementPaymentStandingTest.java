package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.AgreementPaymentStanding;
import in.agreementmitra.signing.PaymentState;
import org.junit.jupiter.api.Test;

/** "Paid under this payment" has one definition; this pins it where the orders view reads it. */
class AgreementPaymentStandingTest {

  private static AgreementPaymentStanding standing(PaymentState state, String storedReference) {
    return new AgreementStanding("AM7K2P9Q", state, storedReference);
  }

  @Test
  void paidUnderTheSameIdInAnotherCase() {
    assertThat(standing(PaymentState.PAID, "PAY_ABC").paidUnder("pay_abc")).isTrue();
  }

  @Test
  void paidUnderTheSameIdWithSurroundingSpaces() {
    assertThat(standing(PaymentState.PAID, "  pay_ABC ").paidUnder("pay_ABC")).isTrue();
    assertThat(standing(PaymentState.PAID, "pay_ABC").paidUnder(" pay_abc  ")).isTrue();
  }

  @Test
  void notPaidUnderAnotherId() {
    assertThat(standing(PaymentState.PAID, "pay_XYZ").paidUnder("pay_ABC")).isFalse();
  }

  @Test
  void aWaivedAgreementIsPaidUnderNothing() {
    assertThat(standing(PaymentState.WAIVED, null).paidUnder("pay_ABC")).isFalse();
    assertThat(standing(PaymentState.WAIVED, "pay_ABC").paidUnder("pay_ABC")).isFalse();
  }

  @Test
  void anUnpaidAgreementIsPaidUnderNothing() {
    assertThat(standing(PaymentState.UNPAID, null).paidUnder("pay_ABC")).isFalse();
  }

  @Test
  void neverTrueWithoutAReferenceOnBothSides() {
    assertThat(standing(PaymentState.PAID, null).paidUnder("pay_ABC")).isFalse();
    assertThat(standing(PaymentState.PAID, "pay_ABC").paidUnder(null)).isFalse();
    assertThat(standing(PaymentState.PAID, null).paidUnder(null)).isFalse();
    assertThat(standing(PaymentState.PAID, " ").paidUnder(" ")).isFalse();
  }

  @Test
  void toStringLeavesTheReferenceOut() {
    assertThat(standing(PaymentState.PAID, "pay_SECRET").toString())
        .contains("AM7K2P9Q")
        .doesNotContain("pay_SECRET");
  }
}

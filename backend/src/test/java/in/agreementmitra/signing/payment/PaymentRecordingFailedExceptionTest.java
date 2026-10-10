package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The failure that replaces a mislabelled duplicate must not carry what the old catch kept out of
 * the log: it escapes to the container, which prints its message and its whole cause chain.
 */
class PaymentRecordingFailedExceptionTest {

  @Test
  void itNamesTheRefusedRuleAndTheSqlState() {
    PaymentRecordingFailedException failure =
        PaymentIntegrity.failure(IntegrityViolations.ofCheckConstraint("ck_payment_order_status"));

    assertThat(failure.refusedRule()).isEqualTo("ck_payment_order_status");
    assertThat(failure.sqlState()).isEqualTo("23514");
    assertThat(failure)
        .hasMessageContaining("ck_payment_order_status")
        .hasMessageContaining("23514");
  }

  @Test
  void aRefusalThatNamesNoRuleIsReportedAsUnnamed() {
    PaymentRecordingFailedException failure =
        PaymentIntegrity.failure(IntegrityViolations.valueTooLong());

    assertThat(failure.refusedRule()).isEqualTo("unnamed");
    assertThat(failure).hasMessageContaining("unnamed").hasMessageContaining("22001");
  }

  @Test
  void aRefusalWithNoSqlStateSaysSo() {
    PaymentRecordingFailedException failure = PaymentIntegrity.failure(IntegrityViolations.bare());

    assertThat(failure.sqlState()).isEqualTo("unknown");
    assertThat(failure).hasMessageContaining("unnamed").hasMessageContaining("unknown");
  }

  @Test
  void itCarriesNothingOfTheOriginalException() {
    for (PaymentRecordingFailedException failure :
        new PaymentRecordingFailedException[] {
          PaymentIntegrity.failure(
              IntegrityViolations.ofCheckConstraint("ck_payment_order_status")),
          PaymentIntegrity.failure(IntegrityViolations.valueTooLong()),
          PaymentIntegrity.failure(IntegrityViolations.bare())
        }) {
      assertThat(failure).hasNoCause().hasNoSuppressedExceptions();
      assertThat(failure.getMessage())
          .doesNotContain(IntegrityViolations.DATABASE_TEXT)
          .doesNotContain("pay_LEAKED")
          .doesNotContain("could not execute statement");
      assertThat(failure.toString()).doesNotContain("pay_LEAKED");
    }
  }
}

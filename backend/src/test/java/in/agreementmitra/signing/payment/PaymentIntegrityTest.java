package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Which rule refused a payment write, read from the exception chain. */
class PaymentIntegrityTest {

  @Test
  void eachDuplicateRuleIsRecognisedByName() {
    assertThat(
            PaymentIntegrity.violatedConstraint(
                IntegrityViolations.ofConstraint("uq_agreement_payment_reference")))
        .contains(PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE);
    assertThat(
            PaymentIntegrity.violatedConstraint(
                IntegrityViolations.ofConstraint("uq_payment_order_provider_payment_id")))
        .contains(PaymentIntegrity.ORDER_PROVIDER_PAYMENT_ID);
    assertThat(PaymentIntegrity.GATEWAY_DUPLICATE_RULES)
        .containsExactlyInAnyOrder(
            "uq_agreement_payment_reference", "uq_payment_order_provider_payment_id");
  }

  @Test
  void theNameIsMatchedWhateverItsLetterCase() {
    assertThat(
            PaymentIntegrity.violatedConstraint(
                IntegrityViolations.ofConstraint("UQ_Agreement_Payment_Reference")))
        .contains(PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE);
  }

  @Test
  void anotherConstraintIsReportedButIsNotADuplicateRule() {
    assertThat(
            PaymentIntegrity.violatedConstraint(
                IntegrityViolations.ofCheckConstraint("ck_payment_order_status")))
        .contains("ck_payment_order_status")
        .get()
        .isNotIn(PaymentIntegrity.GATEWAY_DUPLICATE_RULES);
  }

  @Test
  void aRefusalThatNamesNoConstraintYieldsNone() {
    assertThat(PaymentIntegrity.violatedConstraint(IntegrityViolations.valueTooLong())).isEmpty();
    assertThat(PaymentIntegrity.violatedConstraint(IntegrityViolations.bare())).isEmpty();
    assertThat(PaymentIntegrity.violatedConstraint(IntegrityViolations.ofConstraint(null)))
        .isEmpty();
    assertThat(PaymentIntegrity.violatedConstraint(IntegrityViolations.ofConstraint(" ")))
        .isEmpty();
  }

  @Test
  void theSqlStateIsReadFromANestedSqlException() {
    assertThat(PaymentIntegrity.sqlState(IntegrityViolations.valueTooLong())).isEqualTo("22001");
    assertThat(PaymentIntegrity.sqlState(IntegrityViolations.ofConstraint("anything")))
        .isEqualTo("23505");
    assertThat(
            PaymentIntegrity.sqlState(
                new DataIntegrityViolationException(
                    "outer", new RuntimeException("middle", new SQLException("inner", "22003")))))
        .isEqualTo("22003");
  }

  @Test
  void aChainWithNoSqlExceptionHasNoSqlState() {
    assertThat(PaymentIntegrity.sqlState(IntegrityViolations.bare())).isNull();
  }
}

package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.AgreementIds;
import in.agreementmitra.ConflictException;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.support.LogCapture;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What a refused manual confirmation is reported as. Staff are told a reference was reused only
 * when the reference rule is what refused the write.
 */
class PaymentServiceTest {

  private static final UUID STAFF = UUID.randomUUID();
  private static final UUID AGREEMENT = UUID.randomUUID();
  private static final String REFERENCE = "PAY-MANUAL-7781";

  @RegisterExtension final LogCapture logs = LogCapture.of(PaymentService.class, Level.DEBUG);

  private AgreementService agreementService;
  private PaymentService service;

  @BeforeEach
  void setUp() {
    agreementService = mock(AgreementService.class);
    service = new PaymentService(agreementService);
  }

  private void theDatabaseRefusesWith(DataIntegrityViolationException refusal) {
    when(agreementService.recordPayment(eq(AGREEMENT), any(), eq(STAFF))).thenThrow(refusal);
  }

  private void confirm() {
    service.confirm(STAFF, AGREEMENT, new BigDecimal("1499.00"), "INR", REFERENCE);
  }

  @Test
  void aRefusalByTheReferenceRuleIsAReusedReference() {
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE));

    assertThatThrownBy(this::confirm)
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.kind())
                    .isEqualTo(ConflictException.Kind.PAYMENT_REFERENCE_ALREADY_USED));
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }

  @Test
  void aRefusalByTheOrderPaymentIdRuleIsNotAReusedReference() {
    // This path never writes a payment order, so that rule refusing it would itself be unexpected.
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.ORDER_PROVIDER_PAYMENT_ID));

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);
  }

  @Test
  void aRefusalByAnotherConstraintIsAPaymentRecordingFailure() {
    theDatabaseRefusesWith(IntegrityViolations.ofCheckConstraint("ck_agreement_payment_state"));

    assertThatThrownBy(this::confirm)
        .isInstanceOf(PaymentRecordingFailedException.class)
        .hasNoCause();
  }

  @Test
  void aRefusalThatNamesNoConstraintIsAPaymentRecordingFailure() {
    theDatabaseRefusesWith(IntegrityViolations.valueTooLong());

    assertThatThrownBy(this::confirm)
        .isInstanceOf(PaymentRecordingFailedException.class)
        .hasNoCause();
  }

  @Test
  void theFailureIsLoggedOnceWithTheRuleAndSqlStateAndNothingOfThePayment() {
    theDatabaseRefusesWith(IntegrityViolations.valueTooLong());

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);

    assertThat(logs.events()).hasSize(1);
    assertThat(logs.hasLevel(Level.ERROR)).isTrue();
    String line = logs.messages().get(0);
    assertThat(line).contains(AgreementIds.redact(AGREEMENT)).contains("unnamed").contains("22001");
    assertThat(line)
        .doesNotContain(AGREEMENT.toString())
        .doesNotContain(REFERENCE)
        .doesNotContain("1499")
        .doesNotContain(IntegrityViolations.DATABASE_TEXT);
    assertThat(logs.throwableMessages()).isEmpty();
  }
}

package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.JurisdictionEligibility;
import in.agreementmitra.signing.agreement.StampQuoteRecordRepository;
import in.agreementmitra.signing.agreement.StampQuoting;
import in.agreementmitra.signing.contact.PartyReachability;
import in.agreementmitra.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What a refused gateway confirmation is reported as. No Spring context and no database: the
 * transactional write is mocked to refuse, because what is under test is the decision made about
 * the refusal.
 */
class PaymentOrderServiceTest {

  private static final String ORDER_ID = "order_RZP9f3k1";
  private static final String PAYMENT_ID = "pay_RZP8b2j0";

  @RegisterExtension final LogCapture logs = LogCapture.of(PaymentOrderService.class, Level.DEBUG);

  private PaymentConfirmations confirmations;
  private PaymentOrderService service;

  @BeforeEach
  void setUp() {
    confirmations = mock(PaymentConfirmations.class);
    service =
        new PaymentOrderService(
            mock(PaymentOrderRepository.class),
            mock(PaymentPricing.class),
            mock(RazorpayClient.class),
            confirmations,
            mock(AgreementService.class),
            mock(PaymentProperties.class),
            mock(PartyReachability.class),
            mock(JurisdictionEligibility.class),
            mock(StampQuoting.class),
            mock(StampQuoteRecordRepository.class),
            mock(PlatformTransactionManager.class));
  }

  private void theDatabaseRefusesWith(DataIntegrityViolationException refusal) {
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR")).thenThrow(refusal);
  }

  private ConfirmationOutcome confirm() {
    return service.applyConfirmation(ORDER_ID, PAYMENT_ID, 49_900L, "INR");
  }

  @Test
  void aPaymentAlreadyRecordedAgainstAnotherAgreementIsADuplicateReference() {
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE));

    assertThat(confirm()).isEqualTo(ConfirmationOutcome.DUPLICATE_REFERENCE);
  }

  @Test
  void aPaymentIdAlreadyHeldByAnotherOrderIsADuplicateReference() {
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.ORDER_PROVIDER_PAYMENT_ID));

    assertThat(confirm()).isEqualTo(ConfirmationOutcome.DUPLICATE_REFERENCE);
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }

  @Test
  void aRefusalByAnotherConstraintIsAPaymentRecordingFailure() {
    theDatabaseRefusesWith(IntegrityViolations.ofCheckConstraint("ck_payment_order_status"));

    PaymentRecordingFailedException failure =
        catchThrowableOfType(PaymentRecordingFailedException.class, this::confirm);

    assertThat(failure.refusedRule()).isEqualTo("ck_payment_order_status");
    assertThat(failure).hasNoCause();
  }

  @Test
  void aRefusalThatNamesNoConstraintIsAPaymentRecordingFailure() {
    theDatabaseRefusesWith(IntegrityViolations.valueTooLong());

    assertThatThrownBy(this::confirm)
        .isInstanceOf(PaymentRecordingFailedException.class)
        .hasNoCause();
  }

  @Test
  void aRefusalWithNothingBehindItIsAPaymentRecordingFailure() {
    theDatabaseRefusesWith(IntegrityViolations.bare());

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);
  }

  @Test
  void theFailureIsLoggedOnceWithTheRuleAndSqlStateAndNothingOfThePayment() {
    theDatabaseRefusesWith(IntegrityViolations.valueTooLong());

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);

    assertThat(logs.events()).hasSize(1);
    assertThat(logs.hasLevel(Level.ERROR)).isTrue();
    String line = logs.messages().get(0);
    assertThat(line)
        .contains(RazorpayClient.redact(ORDER_ID))
        .contains("unnamed")
        .contains("22001");
    assertThat(line)
        .doesNotContain(ORDER_ID)
        .doesNotContain(PAYMENT_ID)
        .doesNotContain("49900")
        .doesNotContain(IntegrityViolations.DATABASE_TEXT);
    // The refusal is not handed to the logger: its message is the SQL and the failing row.
    assertThat(logs.throwableMessages()).isEmpty();
  }
}

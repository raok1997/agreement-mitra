package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.JurisdictionEligibility;
import in.agreementmitra.signing.agreement.StampQuoteRecordRepository;
import in.agreementmitra.signing.agreement.StampQuoting;
import in.agreementmitra.signing.api.CheckoutCallbackRequest;
import in.agreementmitra.signing.contact.PartyReachability;
import in.agreementmitra.support.LogCapture;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
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

  private static final PaymentEventOrigin ORIGIN =
      PaymentEventOrigin.webhook("evt_1", "payment.captured");

  private PaymentConfirmations confirmations;
  private PaymentEventLedger ledger;
  private PaymentOrderRepository orders;
  private RazorpayClient razorpay;
  private AgreementService agreements;
  private PaymentOrderService service;

  @BeforeEach
  void setUp() {
    confirmations = mock(PaymentConfirmations.class);
    ledger = mock(PaymentEventLedger.class);
    orders = mock(PaymentOrderRepository.class);
    razorpay = mock(RazorpayClient.class);
    agreements = mock(AgreementService.class);
    service =
        new PaymentOrderService(
            orders,
            mock(PaymentPricing.class),
            razorpay,
            confirmations,
            ledger,
            agreements,
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
    return service.applyConfirmation(ORIGIN, ORDER_ID, PAYMENT_ID, 49_900L, "INR");
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

  // --- the payment event ledger (payment-event-ledger) ---------------------------

  /** The one ledger call an attempt makes, with the outcome, rule and SQL state it carried. */
  private void verifyRecordedOnce(PaymentEventOutcome outcome, String rule, String sqlState) {
    verify(ledger, times(1))
        .record(
            eq(ORIGIN),
            eq(ORDER_ID),
            eq(PAYMENT_ID),
            eq(49_900L),
            eq("INR"),
            any(Instant.class),
            eq(outcome),
            eq(rule),
            eq(sqlState));
    verifyNoMoreInteractions(ledger);
  }

  @Test
  void theReturnedOutcomeIsRecordedOnceAfterTheConfirmationHasReturned() {
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR"))
        .thenReturn(ConfirmationOutcome.CONFIRMED);

    assertThat(confirm()).isEqualTo(ConfirmationOutcome.CONFIRMED);

    // After, not inside: a ledger fault must never be catchable as a refusal of the payment.
    InOrder order = inOrder(confirmations, ledger);
    order.verify(confirmations).apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR");
    order.verify(ledger).record(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    verifyRecordedOnce(PaymentEventOutcome.CONFIRMED, null, null);
  }

  /**
   * The ledger never throws, but the shape must not depend on that. Were the record inside the
   * {@code try}, a ledger fault shaped like a refusal would be reported as the payment's own
   * refusal, and recorded a second time.
   */
  @Test
  void aLedgerFaultAfterAConfirmedPaymentIsNeverClassifiedAsARefusalOfThePayment() {
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR"))
        .thenReturn(ConfirmationOutcome.CONFIRMED);
    DataIntegrityViolationException ledgerFault = IntegrityViolations.valueTooLong();
    doThrow(ledgerFault)
        .when(ledger)
        .record(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());

    assertThatThrownBy(this::confirm).isSameAs(ledgerFault);

    verifyRecordedOnce(PaymentEventOutcome.CONFIRMED, null, null);
    assertThat(logs.events()).isEmpty();
  }

  @Test
  void aRefusedDuplicateIsRecordedWithTheRuleThatRefusedIt() {
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE));

    confirm();

    verifyRecordedOnce(
        PaymentEventOutcome.DUPLICATE_REFERENCE,
        PaymentIntegrity.AGREEMENT_PAYMENT_REFERENCE,
        "23505");
  }

  @Test
  void aDuplicateRefusedByTheOrderRuleIsRecordedWithThatRule() {
    theDatabaseRefusesWith(
        IntegrityViolations.ofConstraint(PaymentIntegrity.ORDER_PROVIDER_PAYMENT_ID));

    confirm();

    verifyRecordedOnce(
        PaymentEventOutcome.DUPLICATE_REFERENCE,
        PaymentIntegrity.ORDER_PROVIDER_PAYMENT_ID,
        "23505");
  }

  @Test
  void aRecordingFailureIsRecordedOnceAndStillThrown() {
    theDatabaseRefusesWith(IntegrityViolations.ofCheckConstraint("ck_payment_order_status"));

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);

    // Once: the failure raised from the refusal must not also be recorded as ERRORED.
    verifyRecordedOnce(PaymentEventOutcome.RECORDING_FAILED, "ck_payment_order_status", "23514");
  }

  @Test
  void aRecordingFailureThatNamesNoRuleIsRecordedWithItsSqlStateOnly() {
    theDatabaseRefusesWith(IntegrityViolations.valueTooLong());

    assertThatThrownBy(this::confirm).isInstanceOf(PaymentRecordingFailedException.class);

    verifyRecordedOnce(PaymentEventOutcome.RECORDING_FAILED, null, "22001");
  }

  @Test
  void anyOtherErrorIsRecordedAsErroredAndReachesTheCallerUnchanged() {
    IllegalStateException failure = new IllegalStateException(IntegrityViolations.DATABASE_TEXT);
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR")).thenThrow(failure);

    assertThatThrownBy(this::confirm).isSameAs(failure);

    verifyRecordedOnce(PaymentEventOutcome.ERRORED, null, null);
  }

  @Test
  void anotherErrorThatCarriesASqlStateIsRecordedWithIt() {
    CannotAcquireLockException failure =
        new CannotAcquireLockException(
            "could not serialize", new SQLException(IntegrityViolations.DATABASE_TEXT, "40001"));
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR")).thenThrow(failure);

    assertThatThrownBy(this::confirm).isSameAs(failure);

    verifyRecordedOnce(PaymentEventOutcome.ERRORED, null, "40001");
  }

  @Test
  void anAuthoritativeReadOfAnUnpaidOrderRecordsNothing() {
    when(razorpay.fetchOrder(ORDER_ID))
        .thenReturn(
            Optional.of(
                new RazorpayClient.ProviderOrder(ORDER_ID, "attempted", 49_900L, "INR", "r")));

    service.readAuthoritatively(PaymentEventOrigin.reconciliation(), ORDER_ID);

    verifyNoInteractions(ledger, confirmations);
  }

  @Test
  void anAuthoritativeReadWithNoCapturedPaymentRecordsNothing() {
    when(razorpay.fetchOrder(ORDER_ID))
        .thenReturn(
            Optional.of(new RazorpayClient.ProviderOrder(ORDER_ID, "paid", 49_900L, "INR", "r")));
    when(razorpay.capturedPaymentFor(ORDER_ID)).thenReturn(Optional.empty());

    assertThat(service.readAuthoritatively(PaymentEventOrigin.reconciliation(), ORDER_ID))
        .isEqualTo(ConfirmationOutcome.MISSING_REFERENCE);

    verifyNoInteractions(ledger, confirmations);
  }

  /**
   * The browser presents a payment id; the gateway's own answer is what is applied and recorded. A
   * value that travelled through the browser never reaches the ledger.
   */
  @Test
  void theBrowserCallbackRecordsThePaymentIdTheGatewayReported() {
    UUID agreementId = UUID.randomUUID();
    UUID caller = UUID.randomUUID();
    String presented = "pay_FROMBROWSER";
    when(agreements.isAccessibleBy(agreementId, caller)).thenReturn(true);
    when(razorpay.keySecret()).thenReturn("key-1");
    when(orders.findByProviderOrderId(ORDER_ID))
        .thenReturn(
            Optional.of(
                PaymentOrder.create(
                    agreementId, ORDER_ID, "receipt", new Money(49_900L, "INR"), Instant.now())));
    when(razorpay.fetchOrder(ORDER_ID))
        .thenReturn(
            Optional.of(new RazorpayClient.ProviderOrder(ORDER_ID, "paid", 49_900L, "INR", "r")));
    when(razorpay.capturedPaymentFor(ORDER_ID))
        .thenReturn(
            Optional.of(
                new RazorpayClient.ProviderPayment(PAYMENT_ID, "captured", 49_900L, "INR")));
    when(confirmations.apply(ORDER_ID, PAYMENT_ID, 49_900L, "INR"))
        .thenReturn(ConfirmationOutcome.CONFIRMED);

    service.acknowledgeCheckout(
        agreementId,
        new CheckoutCallbackRequest(
            ORDER_ID,
            presented,
            RazorpaySignatures.hmacSha256Hex(ORDER_ID + "|" + presented, "key-1")),
        caller,
        false);

    verify(ledger, times(1))
        .record(
            eq(PaymentEventOrigin.browserCallback()),
            eq(ORDER_ID),
            eq(PAYMENT_ID),
            eq(49_900L),
            eq("INR"),
            any(Instant.class),
            eq(PaymentEventOutcome.CONFIRMED),
            eq(null),
            eq(null));
    verifyNoMoreInteractions(ledger);
  }
}

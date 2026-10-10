package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.PaymentRecording;
import in.agreementmitra.signing.agreement.AgreementService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What {@code apply} does with each answer the agreement gives. The webhook, the browser callback's
 * authoritative read and reconciliation all converge on this method, so this covers every route.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmationsTest {

  private static final String ORDER_ID = "order_UNIT1";
  private static final String PAYMENT_ID = "pay_UNIT1";
  private static final long AMOUNT = 49_900L;

  @Mock private PaymentOrderRepository orders;
  @Mock private AgreementService agreementService;
  @Mock private ApplicationEventPublisher events;

  private final UUID agreementId = UUID.randomUUID();
  private PaymentConfirmations confirmations;
  private PaymentOrder order;

  @BeforeEach
  void anOutstandingOrder() {
    confirmations = new PaymentConfirmations(orders, agreementService, events);
    order =
        PaymentOrder.create(
            agreementId, ORDER_ID, agreementId.toString(), new Money(AMOUNT, "INR"), Instant.now());
  }

  private void theOrderIsHeld() {
    when(orders.findByProviderOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
  }

  private void theAgreementAnswers(PaymentRecording recording) {
    when(agreementService.recordGatewayPayment(eq(agreementId), any())).thenReturn(recording);
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentRecording.class,
      names = {"RECORDED", "ALREADY_RECORDED"})
  void aPaymentThatIsTheAgreementsOwnMarksTheOrderPaidAndPublishesTheEvent(
      PaymentRecording recording) {
    theOrderIsHeld();
    theAgreementAnswers(recording);

    ConfirmationOutcome outcome = confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "INR");

    assertThat(outcome).isEqualTo(ConfirmationOutcome.CONFIRMED);
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.PAID);
    assertThat(order.providerPaymentId()).isEqualTo(PAYMENT_ID);
    assertThat(order.surplus()).isFalse();
    verify(orders).save(order);
    verify(events).publishEvent(new PaymentConfirmedEvent(agreementId));
  }

  @Test
  void aSurplusPaymentMarksTheOrderPaidAndSurplusAndPublishesNothing() {
    theOrderIsHeld();
    theAgreementAnswers(PaymentRecording.SURPLUS);

    ConfirmationOutcome outcome = confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "INR");

    assertThat(outcome).isEqualTo(ConfirmationOutcome.CONFIRMED);
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.PAID);
    assertThat(order.providerPaymentId()).isEqualTo(PAYMENT_ID);
    assertThat(order.confirmedAt()).isNotNull();
    assertThat(order.surplus()).isTrue();
    verify(orders).save(order);
    verifyNoInteractions(events);
  }

  @Test
  void aLateConfirmationOnAnExpiredOrderStillReachesTheAgreement() {
    order.markExpired();
    theOrderIsHeld();
    theAgreementAnswers(PaymentRecording.SURPLUS);

    assertThat(confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "INR"))
        .isEqualTo(ConfirmationOutcome.CONFIRMED);
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.PAID);
    assertThat(order.surplus()).isTrue();
  }

  @Test
  void theConfirmationHandedToTheAgreementCarriesTheRawPaymentIdAndTheOrdersAmount() {
    theOrderIsHeld();
    theAgreementAnswers(PaymentRecording.RECORDED);

    confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "INR");

    ArgumentCaptor<PaymentConfirmation> sent = ArgumentCaptor.forClass(PaymentConfirmation.class);
    verify(agreementService).recordGatewayPayment(eq(agreementId), sent.capture());
    assertThat(sent.getValue().reference()).isEqualTo(PAYMENT_ID);
    assertThat(sent.getValue().amount()).isEqualByComparingTo("499.00");
    assertThat(sent.getValue().currency()).isEqualTo("INR");
  }

  // --- the earlier refusals are unchanged ---------------------------------------

  @Test
  void anUnknownOrderIsNotApplied() {
    when(orders.findByProviderOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

    assertThat(confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "INR"))
        .isEqualTo(ConfirmationOutcome.UNKNOWN_ORDER);
    verifyNoInteractions(agreementService, events);
  }

  @Test
  void anAlreadySettledOrderIsNotAppliedAgainAndKeepsItsSurplusMark() {
    order.markPaid(PAYMENT_ID, Instant.now(), true);
    theOrderIsHeld();

    assertThat(confirmations.apply(ORDER_ID, "pay_OTHER", AMOUNT, "INR"))
        .isEqualTo(ConfirmationOutcome.ALREADY_CONFIRMED);
    assertThat(order.surplus()).isTrue();
    assertThat(order.providerPaymentId()).isEqualTo(PAYMENT_ID);
    verifyNoInteractions(agreementService, events);
    verify(orders, never()).save(any());
  }

  @Test
  void anAmountOrCurrencyMismatchIsRefused() {
    theOrderIsHeld();

    assertThat(confirmations.apply(ORDER_ID, PAYMENT_ID, 1L, "INR"))
        .isEqualTo(ConfirmationOutcome.AMOUNT_MISMATCH);
    assertThat(confirmations.apply(ORDER_ID, PAYMENT_ID, AMOUNT, "USD"))
        .isEqualTo(ConfirmationOutcome.AMOUNT_MISMATCH);
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.CREATED);
    verifyNoInteractions(agreementService, events);
  }

  @Test
  void aConfirmationWithNoPaymentIdIsDeferred() {
    theOrderIsHeld();

    assertThat(confirmations.apply(ORDER_ID, " ", AMOUNT, "INR"))
        .isEqualTo(ConfirmationOutcome.MISSING_REFERENCE);
    assertThat(confirmations.apply(ORDER_ID, null, AMOUNT, "INR"))
        .isEqualTo(ConfirmationOutcome.MISSING_REFERENCE);
    assertThat(order.status()).isEqualTo(PaymentOrderStatus.CREATED);
    verifyNoInteractions(agreementService, events);
  }
}

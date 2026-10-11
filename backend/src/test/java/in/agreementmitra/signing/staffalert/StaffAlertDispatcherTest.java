package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SurplusPayment;
import in.agreementmitra.signing.UnrecordedPayment;
import in.agreementmitra.signing.UnrecordedPayment.Reason;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.agreement.StaffAgreementView;
import in.agreementmitra.signing.agreement.StaffPartyView;
import in.agreementmitra.signing.contact.DeliveryChannelProperties;
import in.agreementmitra.signing.staffalert.StaffAlertDeliveryException.Kind;
import in.agreementmitra.support.LogCapture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The sweep with every collaborator mocked: that it does nothing at all with no channel configured,
 * and how it enqueues and dispatches each kind of alert when one is.
 */
@ExtendWith(MockitoExtension.class)
class StaffAlertDispatcherTest {

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  @Mock private StaffNotifier notifier;
  @Mock private PaymentOrderQuery paymentOrders;
  @Mock private StaffAlertPersistence persistence;
  @Mock private AgreementService agreements;

  private final Instant now = Instant.now();
  private StaffAlertDispatcher dispatcher;

  @BeforeEach
  void dispatcher() {
    dispatcher =
        new StaffAlertDispatcher(
            notifier,
            paymentOrders,
            persistence,
            agreements,
            new DeliveryChannelProperties("https://app.example.test", null));
  }

  private void channelConfigured(boolean configured) {
    when(notifier.configured()).thenReturn(configured);
  }

  private static StaffAlert pending(UUID id, StaffAlertKind kind, UUID agreementId, Instant at) {
    StaffAlert alert = new StaffAlert();
    ReflectionTestUtils.setField(alert, "id", id);
    ReflectionTestUtils.setField(alert, "kind", kind);
    ReflectionTestUtils.setField(alert, "agreementId", agreementId);
    ReflectionTestUtils.setField(alert, "status", StaffAlertStatus.PENDING);
    ReflectionTestUtils.setField(alert, "nextAttemptAt", at);
    ReflectionTestUtils.setField(alert, "createdAt", at);
    return alert;
  }

  private static UnrecordedPayment unrecorded(Reason reason) {
    return new UnrecordedPayment(UUID.randomUUID(), UUID.randomUUID(), reason);
  }

  private void theAgreementIsViewable(UUID agreementId) {
    when(agreements.staffViewsByAgreementId(List.of(agreementId)))
        .thenReturn(
            Map.of(
                agreementId,
                new StaffAgreementView(
                    agreementId,
                    "AM7K2P9Q",
                    "Hyderabad",
                    LocalDate.of(2026, 1, 1),
                    "Residential rental agreement",
                    "TG",
                    List.of(new StaffPartyView(Role.OWNER, "Asha Owner", "Ravi Owner")))));
  }

  // --- no channel configured -----------------------------------------------------

  @Test
  void aFullSweepReadsNothingRecordsNothingAndSendsNothing() {
    channelConfigured(false);

    dispatcher.dispatchDue();

    verifyNoInteractions(paymentOrders, persistence, agreements);
    verify(notifier, never()).send(any());
  }

  @Test
  void eachStepIsANoOpOnItsOwn() {
    channelConfigured(false);

    dispatcher.enqueue(now);
    assertThat(dispatcher.dueAlertIds(now)).isEmpty();
    dispatcher.dispatchOne(new StaffAlertKey(UUID.randomUUID(), StaffAlertKind.ORDER_PAID), now);

    verifyNoInteractions(paymentOrders, persistence, agreements);
    verify(notifier, never()).send(any());
  }

  // --- the two kinds (double-charge-invisible-to-staff) --------------------------

  @Test
  void enqueueHandsPaidAgreementsAndSurplusOrdersToPersistence() {
    channelConfigured(true);
    Instant cutoff = now.minus(StaffAlertBackoff.WINDOW);
    List<UUID> paid = List.of(UUID.randomUUID());
    List<SurplusPayment> surplus =
        List.of(new SurplusPayment(UUID.randomUUID(), UUID.randomUUID()));
    List<UnrecordedPayment> unrecorded = List.of(unrecorded(Reason.NOT_RECORDED));
    when(paymentOrders.agreementsWithOrderPaidSince(cutoff)).thenReturn(paid);
    when(paymentOrders.surplusOrdersPaidSince(cutoff)).thenReturn(surplus);
    when(paymentOrders.unrecordedPaymentsSince(cutoff)).thenReturn(unrecorded);

    dispatcher.enqueue(now);

    verify(persistence).enqueue(paid, surplus, now);
    verify(persistence).enqueueUnrecorded(unrecorded, now);
  }

  @Test
  void enqueueWithOnlyAnUnrecordedPaymentStillRecordsIt() {
    channelConfigured(true);
    List<UnrecordedPayment> unrecorded = List.of(unrecorded(Reason.AMOUNT_MISMATCH));
    when(paymentOrders.unrecordedPaymentsSince(any())).thenReturn(unrecorded);

    dispatcher.enqueue(now);

    verify(persistence).enqueueUnrecorded(unrecorded, now);
    verify(persistence, never()).enqueue(any(), any(), any());
  }

  @Test
  void enqueueWithOnlyASurplusOrderStillRecordsIt() {
    channelConfigured(true);
    List<SurplusPayment> surplus =
        List.of(new SurplusPayment(UUID.randomUUID(), UUID.randomUUID()));
    when(paymentOrders.surplusOrdersPaidSince(any())).thenReturn(surplus);

    dispatcher.enqueue(now);

    verify(persistence).enqueue(List.of(), surplus, now);
  }

  @Test
  void enqueueWithNothingPaidOpensNoTransaction() {
    channelConfigured(true);

    dispatcher.enqueue(now);

    verify(persistence, never()).enqueue(any(), any(), any());
    verify(persistence, never()).enqueueUnrecorded(any(), any());
  }

  // --- the ledger look-back cannot stop the sweep (payment-recording-failure-staff-alert D1) ---

  @Test
  void aLedgerReadThatThrowsLeavesTheOtherLookBacksRecordedAndTheDispatchRunning() {
    channelConfigured(true);
    List<UUID> paid = List.of(UUID.randomUUID());
    List<SurplusPayment> surplus =
        List.of(new SurplusPayment(UUID.randomUUID(), UUID.randomUUID()));
    when(paymentOrders.agreementsWithOrderPaidSince(any())).thenReturn(paid);
    when(paymentOrders.surplusOrdersPaidSince(any())).thenReturn(surplus);
    when(paymentOrders.unrecordedPaymentsSince(any()))
        .thenThrow(new IllegalStateException("relation payment_event: secret detail"));

    dispatcher.dispatchDue();

    verify(persistence).enqueue(eq(paid), eq(surplus), any());
    verify(persistence, never()).enqueueUnrecorded(any(), any());
    verify(persistence).dueIds(any(), anyInt());
    assertTheLookBackFailureIsTheOnlyError("IllegalStateException", "secret detail");
  }

  @Test
  void anUnrecordedInsertThatThrowsLeavesTheOtherLookBacksRecordedAndTheDispatchRunning() {
    channelConfigured(true);
    List<UUID> paid = List.of(UUID.randomUUID());
    List<SurplusPayment> surplus =
        List.of(new SurplusPayment(UUID.randomUUID(), UUID.randomUUID()));
    List<UnrecordedPayment> unrecorded = List.of(unrecorded(Reason.ID_REUSED));
    when(paymentOrders.agreementsWithOrderPaidSince(any())).thenReturn(paid);
    when(paymentOrders.surplusOrdersPaidSince(any())).thenReturn(surplus);
    when(paymentOrders.unrecordedPaymentsSince(any())).thenReturn(unrecorded);
    org.mockito.Mockito.doThrow(new IllegalArgumentException("violates check: secret detail"))
        .when(persistence)
        .enqueueUnrecorded(any(), any());

    dispatcher.dispatchDue();

    verify(persistence).enqueue(eq(paid), eq(surplus), any());
    verify(persistence).dueIds(any(), anyInt());
    assertTheLookBackFailureIsTheOnlyError("IllegalArgumentException", "secret detail");
  }

  /** The ledger look-back runs first, so a fault in the older two does not hold it back. */
  @Test
  void aPaidOrderReadThatThrowsDoesNotStopTheUnrecordedPaymentsBeingRecorded() {
    channelConfigured(true);
    List<UnrecordedPayment> unrecorded = List.of(unrecorded(Reason.NOT_RECORDED));
    when(paymentOrders.unrecordedPaymentsSince(any())).thenReturn(unrecorded);
    when(paymentOrders.agreementsWithOrderPaidSince(any()))
        .thenThrow(new IllegalStateException("lock timeout"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcher.enqueue(now))
        .isInstanceOf(IllegalStateException.class);

    verify(persistence).enqueueUnrecorded(unrecorded, now);
  }

  private void assertTheLookBackFailureIsTheOnlyError(String exceptionClass, String detail) {
    assertThat(logs.events())
        .filteredOn(e -> e.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage())
                  .contains("payment event ledger")
                  .contains(exceptionClass)
                  .doesNotContain(detail);
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void anUnrecordedPaymentAlertIsComposedFromItsAgreementWithItsKind() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    StaffAlertKey key = new StaffAlertKey(paymentOrderId, StaffAlertKind.PAYMENT_NOT_RECORDED);
    when(persistence.find(key))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.PAYMENT_NOT_RECORDED, agreementId, now)));
    when(persistence.claim(eq(key), eq(0), eq(now), any())).thenReturn(true);
    theAgreementIsViewable(agreementId);

    dispatcher.dispatchOne(key, now);

    ArgumentCaptor<StaffAlertMessage> sent = ArgumentCaptor.forClass(StaffAlertMessage.class);
    verify(notifier).send(sent.capture());
    assertThat(sent.getValue().kind()).isEqualTo(StaffAlertKind.PAYMENT_NOT_RECORDED);
    assertThat(sent.getValue().trackingReference()).isEqualTo("AM7K2P9Q");
    verify(persistence).markSent(key, 1, now);
  }

  /** One payment order, two kinds: only the key tells them apart. */
  @Test
  void twoAlertsWithTheSameIdAndDifferentKindsAreSettledIndependently() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    StaffAlertKey notRecorded =
        new StaffAlertKey(paymentOrderId, StaffAlertKind.PAYMENT_NOT_RECORDED);
    StaffAlertKey mismatch =
        new StaffAlertKey(paymentOrderId, StaffAlertKind.PAYMENT_AMOUNT_MISMATCH);
    when(persistence.dueIds(any(), anyInt())).thenReturn(List.of(notRecorded, mismatch));
    when(persistence.find(notRecorded))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.PAYMENT_NOT_RECORDED, agreementId, now)));
    when(persistence.find(mismatch))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.PAYMENT_AMOUNT_MISMATCH, agreementId, now)));
    when(persistence.claim(any(), eq(0), any(), any())).thenReturn(true);
    when(persistence.markFailed(mismatch, 1)).thenReturn(true);
    theAgreementIsViewable(agreementId);
    lenient()
        .doThrow(new StaffAlertDeliveryException(Kind.PERMANENT, 404))
        .when(notifier)
        .send(argThat(m -> m != null && m.kind() == StaffAlertKind.PAYMENT_AMOUNT_MISMATCH));

    dispatcher.dispatchDue();

    verify(persistence).markSent(eq(notRecorded), eq(1), any());
    verify(persistence, never()).markFailed(eq(notRecorded), anyInt());
    verify(persistence).markFailed(mismatch, 1);
    verify(persistence, never()).markSent(eq(mismatch), anyInt(), any());
  }

  @Test
  void aDuplicatePaymentAlertIsComposedFromItsAgreementWithItsKind() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    StaffAlertKey key = new StaffAlertKey(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT);
    when(persistence.find(key))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT, agreementId, now)));
    when(persistence.claim(eq(key), eq(0), eq(now), any())).thenReturn(true);
    theAgreementIsViewable(agreementId);

    dispatcher.dispatchOne(key, now);

    ArgumentCaptor<StaffAlertMessage> sent = ArgumentCaptor.forClass(StaffAlertMessage.class);
    verify(notifier).send(sent.capture());
    assertThat(sent.getValue().kind()).isEqualTo(StaffAlertKind.DUPLICATE_PAYMENT);
    assertThat(sent.getValue().trackingReference()).isEqualTo("AM7K2P9Q");
    verify(persistence).markSent(key, 1, now);
  }

  @Test
  void twoAlertsForOneAgreementInOneSweepAreSettledIndependently() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    StaffAlertKey paidKey = new StaffAlertKey(agreementId, StaffAlertKind.ORDER_PAID);
    StaffAlertKey duplicateKey =
        new StaffAlertKey(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT);
    when(persistence.dueIds(any(), anyInt())).thenReturn(List.of(paidKey, duplicateKey));
    when(persistence.find(paidKey))
        .thenReturn(Optional.of(pending(agreementId, StaffAlertKind.ORDER_PAID, agreementId, now)));
    when(persistence.find(duplicateKey))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT, agreementId, now)));
    when(persistence.claim(any(), eq(0), any(), any())).thenReturn(true);
    when(persistence.markFailed(duplicateKey, 1)).thenReturn(true);
    theAgreementIsViewable(agreementId);
    // Lenient: strict stubs would reject the paid-order send as an argument mismatch.
    lenient()
        .doThrow(new StaffAlertDeliveryException(Kind.PERMANENT, 404))
        .when(notifier)
        .send(argThat(m -> m != null && m.kind() == StaffAlertKind.DUPLICATE_PAYMENT));

    dispatcher.dispatchDue();

    verify(notifier).send(argThat(m -> m.kind() == StaffAlertKind.ORDER_PAID));
    verify(persistence).markSent(eq(paidKey), eq(1), any());
    verify(persistence, never()).markFailed(eq(paidKey), anyInt());
    verify(persistence).markFailed(duplicateKey, 1);
    verify(persistence, never()).markSent(eq(duplicateKey), anyInt(), any());
    assertThat(logs.events())
        .filteredOn(e -> e.getLevel() == Level.ERROR)
        .extracting(e -> e.getFormattedMessage())
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains("DUPLICATE_PAYMENT")
                    .contains(AgreementIds.redact(agreementId))
                    .contains("404")
                    .doesNotContain(agreementId.toString())
                    .doesNotContain(paymentOrderId.toString()));
  }
}

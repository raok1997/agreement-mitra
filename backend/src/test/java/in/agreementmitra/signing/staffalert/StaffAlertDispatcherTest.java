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
 * and how it enqueues and dispatches the two kinds of alert when one is.
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
    dispatcher.dispatchOne(UUID.randomUUID(), now);

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
    when(paymentOrders.agreementsWithOrderPaidSince(cutoff)).thenReturn(paid);
    when(paymentOrders.surplusOrdersPaidSince(cutoff)).thenReturn(surplus);

    dispatcher.enqueue(now);

    verify(persistence).enqueue(paid, surplus, now);
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
  }

  @Test
  void aDuplicatePaymentAlertIsComposedFromItsAgreementWithItsKind() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    when(persistence.find(paymentOrderId))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT, agreementId, now)));
    when(persistence.claim(eq(paymentOrderId), eq(0), eq(now), any())).thenReturn(true);
    theAgreementIsViewable(agreementId);

    dispatcher.dispatchOne(paymentOrderId, now);

    ArgumentCaptor<StaffAlertMessage> sent = ArgumentCaptor.forClass(StaffAlertMessage.class);
    verify(notifier).send(sent.capture());
    assertThat(sent.getValue().kind()).isEqualTo(StaffAlertKind.DUPLICATE_PAYMENT);
    assertThat(sent.getValue().trackingReference()).isEqualTo("AM7K2P9Q");
    verify(persistence).markSent(paymentOrderId, 1, now);
  }

  @Test
  void twoAlertsForOneAgreementInOneSweepAreSettledIndependently() {
    channelConfigured(true);
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    when(persistence.dueIds(any(), anyInt())).thenReturn(List.of(agreementId, paymentOrderId));
    when(persistence.find(agreementId))
        .thenReturn(Optional.of(pending(agreementId, StaffAlertKind.ORDER_PAID, agreementId, now)));
    when(persistence.find(paymentOrderId))
        .thenReturn(
            Optional.of(
                pending(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT, agreementId, now)));
    when(persistence.claim(any(), eq(0), any(), any())).thenReturn(true);
    when(persistence.markFailed(paymentOrderId, 1)).thenReturn(true);
    theAgreementIsViewable(agreementId);
    // Lenient: strict stubs would reject the paid-order send as an argument mismatch.
    lenient()
        .doThrow(new StaffAlertDeliveryException(Kind.PERMANENT, 404))
        .when(notifier)
        .send(argThat(m -> m != null && m.kind() == StaffAlertKind.DUPLICATE_PAYMENT));

    dispatcher.dispatchDue();

    verify(notifier).send(argThat(m -> m.kind() == StaffAlertKind.ORDER_PAID));
    verify(persistence).markSent(eq(agreementId), eq(1), any());
    verify(persistence, never()).markFailed(eq(agreementId), anyInt());
    verify(persistence).markFailed(paymentOrderId, 1);
    verify(persistence, never()).markSent(eq(paymentOrderId), anyInt(), any());
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

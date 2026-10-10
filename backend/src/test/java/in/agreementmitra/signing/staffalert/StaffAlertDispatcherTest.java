package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.contact.DeliveryChannelProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** With no channel configured the sweep does nothing at all: no read, no row, no send. */
@ExtendWith(MockitoExtension.class)
class StaffAlertDispatcherTest {

  @Mock private StaffNotifier notifier;
  @Mock private PaymentOrderQuery paymentOrders;
  @Mock private StaffAlertPersistence persistence;
  @Mock private AgreementService agreements;

  private StaffAlertDispatcher dispatcher;

  @BeforeEach
  void unconfigured() {
    when(notifier.configured()).thenReturn(false);
    dispatcher =
        new StaffAlertDispatcher(
            notifier,
            paymentOrders,
            persistence,
            agreements,
            new DeliveryChannelProperties("https://app.example.test", null));
  }

  @Test
  void aFullSweepReadsNothingRecordsNothingAndSendsNothing() {
    dispatcher.dispatchDue();

    verifyNoInteractions(paymentOrders, persistence, agreements);
    verify(notifier, never()).send(any());
  }

  @Test
  void eachStepIsANoOpOnItsOwn() {
    Instant now = Instant.now();

    dispatcher.enqueue(now);
    assertThat(dispatcher.dueAgreementIds(now)).isEmpty();
    dispatcher.dispatchOne(UUID.randomUUID(), now);

    verifyNoInteractions(paymentOrders, persistence, agreements);
    verify(notifier, never()).send(any());
  }
}

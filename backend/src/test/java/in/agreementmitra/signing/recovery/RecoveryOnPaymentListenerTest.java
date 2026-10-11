package in.agreementmitra.signing.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.PaymentSettledEvent;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.LogCapture;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Whether a settlement turns into a recovery link. The listener sends only for an agreement the
 * "settled and unowned" lookup returns, and nothing it does may throw back into the path that
 * settled the payment.
 */
@ExtendWith(MockitoExtension.class)
class RecoveryOnPaymentListenerTest {

  private static final UUID AGREEMENT_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");

  @Mock private AgreementService agreementService;
  @Mock private RecoveryDeliveryService recoveryDelivery;

  @InjectMocks private RecoveryOnPaymentListener listener;

  @RegisterExtension
  final LogCapture logs = LogCapture.of(RecoveryOnPaymentListener.class, Level.WARN);

  @Test
  void aRecoverableAgreementIsSentItsLink() {
    AgreementResponse agreement = mock(AgreementResponse.class);
    when(agreementService.findRecoverableById(AGREEMENT_ID)).thenReturn(Optional.of(agreement));

    listener.onPaymentSettled(new PaymentSettledEvent(AGREEMENT_ID));

    verify(recoveryDelivery).sendRecoveryLink(agreement);
    assertThat(logs.events()).isEmpty();
  }

  @Test
  void anAgreementThatIsNotRecoverableIsSentNothing() {
    // The lookup returns nothing for an agreement that is owned (or not settled): no link.
    when(agreementService.findRecoverableById(AGREEMENT_ID)).thenReturn(Optional.empty());

    listener.onPaymentSettled(new PaymentSettledEvent(AGREEMENT_ID));

    verifyNoInteractions(recoveryDelivery);
  }

  @Test
  void aLookupFailureIsSwallowedAndLoggedWithTheRedactedIdOnly() {
    when(agreementService.findRecoverableById(AGREEMENT_ID))
        .thenThrow(new IllegalStateException("asha@example.com " + AGREEMENT_ID));

    assertThatCode(() -> listener.onPaymentSettled(new PaymentSettledEvent(AGREEMENT_ID)))
        .doesNotThrowAnyException();

    verifyNoInteractions(recoveryDelivery);
    assertThat(logs.events())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(AgreementIds.redact(AGREEMENT_ID))
                  .doesNotContain(AGREEMENT_ID.toString())
                  .doesNotContain("asha@example.com");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }
}

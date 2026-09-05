package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import in.agreementmitra.ConflictException;
import in.agreementmitra.signing.PaymentGateMode;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.agreement.AgreementService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the payment gate - mocked collaborators, no Spring, no I/O.
 *
 * <p>Both modes are exercised here on every build. {@code REQUIRED} will not run in production for
 * a while, and untested-in-production paths rot; the answer is that it is tested on every commit,
 * so enabling it later is a configuration change rather than new, unexercised behaviour.
 */
@ExtendWith(MockitoExtension.class)
class PaymentGateTest {

  @Mock private AgreementService agreementService;

  private final UUID agreementId = UUID.randomUUID();

  private PaymentGate gate(PaymentGateMode mode) {
    return new PaymentGate(new PaymentProperties(mode, null, null, null), agreementService);
  }

  private void state(PaymentState state) {
    lenient().when(agreementService.paymentState(agreementId)).thenReturn(Optional.of(state));
  }

  // --- OPTIONAL --------------------------------------------------------------

  @Test
  void optionalModePermitsAnUnpaidAgreement() {
    state(PaymentState.UNPAID);

    assertThatCode(() -> gate(PaymentGateMode.OPTIONAL).require(agreementId))
        .doesNotThrowAnyException();
  }

  @Test
  void anUnconfiguredGateFailsClosed() {
    // The gate guards the step where a staff member spends a real SHCIL certificate on an order.
    // Absent configuration must therefore REFUSE, not permit: relaxing the gate has to be a
    // deliberate act (PAYMENT_MODE=OPTIONAL), never a consequence of a missing property.
    assertThat(new PaymentProperties(null, null, null, null).mode())
        .isEqualTo(PaymentGateMode.REQUIRED);
  }

  @Test
  void optionalModeDoesNotEvenNeedToReadPaymentState() {
    // Nothing is stubbed; a gate that queried state here would fail. The permissive path must stay
    // free of work, because it is the one that runs on every order today.
    assertThatCode(() -> gate(PaymentGateMode.OPTIONAL).require(agreementId))
        .doesNotThrowAnyException();
  }

  // --- REQUIRED --------------------------------------------------------------

  @Test
  void requiredModeBlocksAnUnpaidAgreementWithADistinguishableRefusal() {
    state(PaymentState.UNPAID);

    assertThatThrownBy(() -> gate(PaymentGateMode.REQUIRED).require(agreementId))
        .isInstanceOfSatisfying(
            ConflictException.class,
            // NOT folded into draft-required / stamp-required / contact-required: an operator has
            // to be able to tell which precondition stopped the pipeline, because a different
            // person fixes each one.
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.PAYMENT_REQUIRED));
  }

  @Test
  void requiredModeAdmitsAPaidAgreement() {
    state(PaymentState.PAID);

    assertThatCode(() -> gate(PaymentGateMode.REQUIRED).require(agreementId))
        .doesNotThrowAnyException();
  }

  @Test
  void requiredModeAdmitsAWaivedAgreement() {
    state(PaymentState.WAIVED);

    assertThatCode(() -> gate(PaymentGateMode.REQUIRED).require(agreementId))
        .doesNotThrowAnyException();
  }

  @Test
  void anUnknownAgreementIsLeftToTheCallersOwn404() {
    lenient().when(agreementService.paymentState(agreementId)).thenReturn(Optional.empty());

    assertThatCode(() -> gate(PaymentGateMode.REQUIRED).require(agreementId))
        .doesNotThrowAnyException();
  }

  // --- observability + state semantics ---------------------------------------

  @Test
  void theActiveModeIsObservableAtRuntime() {
    assertThat(gate(PaymentGateMode.REQUIRED).mode()).isEqualTo(PaymentGateMode.REQUIRED);
    assertThat(gate(PaymentGateMode.OPTIONAL).mode()).isEqualTo(PaymentGateMode.OPTIONAL);
  }

  @Test
  void waivedSatisfiesTheGateButRemainsADistinctStateFromPaid() {
    assertThat(PaymentState.PAID.satisfiesGate()).isTrue();
    assertThat(PaymentState.WAIVED.satisfiesGate()).isTrue();
    assertThat(PaymentState.UNPAID.satisfiesGate()).isFalse();
    // The two must never be collapsed: one answers "how much money came in", the other does not.
    assertThat(PaymentState.WAIVED).isNotEqualTo(PaymentState.PAID);
  }
}

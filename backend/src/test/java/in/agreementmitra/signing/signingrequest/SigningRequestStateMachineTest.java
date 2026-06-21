package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import in.agreementmitra.signing.SignatureStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link SigningRequest} state machine — pure entity logic, no Spring, no I/O.
 * Covers the legal lifecycle, illegal/terminal transitions, and idempotent re-delivery.
 */
class SigningRequestStateMachineTest {

  @Test
  void newRequestStartsInPdfGenerated() {
    SigningRequest request = SigningRequest.createPending(UUID.randomUUID());
    assertThat(request.status()).isEqualTo(SignatureStatus.PDF_GENERATED);
    assertThat(request.providerDocumentId()).isNull();
  }

  @Test
  void markRequestedRecordsDocumentIdUrlsAndAdvancesToSignRequested() {
    SigningRequest request = SigningRequest.createPending(UUID.randomUUID());
    UUID signerId = UUID.randomUUID();

    request.markRequested(
        "DOC-1",
        List.of(
            SigningRequestInvitee.create(signerId, "https://sign/abc", "2026-12-31", 0, "INV-1")));

    assertThat(request.status()).isEqualTo(SignatureStatus.SIGN_REQUESTED);
    assertThat(request.providerDocumentId()).isEqualTo("DOC-1");
    assertThat(request.invitees()).hasSize(1);
    assertThat(request.invitees().get(0).signerId()).isEqualTo(signerId);
  }

  @Test
  void signRequestedTransitionsToEachTerminalState() {
    for (SignatureStatus terminal :
        List.of(SignatureStatus.SIGNED, SignatureStatus.FAILED, SignatureStatus.EXPIRED)) {
      SigningRequest request = requested();
      request.transitionTo(terminal);
      assertThat(request.status()).isEqualTo(terminal);
    }
  }

  @Test
  void transitionOutOfTerminalIsRejected() {
    SigningRequest request = requested();
    request.transitionTo(SignatureStatus.SIGNED);

    assertThatThrownBy(() -> request.transitionTo(SignatureStatus.FAILED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(request.status()).isEqualTo(SignatureStatus.SIGNED);
  }

  @Test
  void illegalForwardTransitionIsRejected() {
    SigningRequest request = SigningRequest.createPending(UUID.randomUUID());
    // PDF_GENERATED cannot jump straight to SIGNED.
    assertThatThrownBy(() -> request.transitionTo(SignatureStatus.SIGNED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(request.status()).isEqualTo(SignatureStatus.PDF_GENERATED);
  }

  @Test
  void sameTerminalTransitionIsIdempotent() {
    SigningRequest request = requested();
    request.transitionTo(SignatureStatus.SIGNED);

    assertDoesNotThrow(() -> request.transitionTo(SignatureStatus.SIGNED));
    assertThat(request.status()).isEqualTo(SignatureStatus.SIGNED);
  }

  private static SigningRequest requested() {
    SigningRequest request = SigningRequest.createPending(UUID.randomUUID());
    request.markRequested("DOC", List.of());
    return request;
  }
}

package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.DocumentStatusView.InviteeStatusView;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignatureStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain unit tests for the per-invitee aggregation on {@link SigningRequest} — no Spring, no
 * I/O. Verifies the FSM is driven from per-invitee status counts (correlation-independent) and the
 * precedence rule, plus idempotency and the artifact-needed flag.
 */
class SigningRequestAggregationTest {

  private static SigningRequest twoInviteeRequest() {
    SigningRequest request = SigningRequest.createPending(UUID.randomUUID());
    request.markRequested(
        "DOC-1",
        List.of(
            SigningRequestInvitee.create(UUID.randomUUID(), "url-0", "2026", 0, "INV-0"),
            SigningRequestInvitee.create(UUID.randomUUID(), "url-1", "2026", 1, "INV-1")));
    return request;
  }

  private static DocumentStatusView view(InviteeStatus first, InviteeStatus second) {
    return new DocumentStatusView(
        List.of(
            new InviteeStatusView("INV-0", 0, first), new InviteeStatusView("INV-1", 1, second)));
  }

  @Test
  void allSignedDrivesSigned() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.SIGNED));
    assertThat(request.status()).isEqualTo(SignatureStatus.SIGNED);
  }

  @Test
  void partialSigningStaysSignRequested() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.PENDING));
    assertThat(request.status()).isEqualTo(SignatureStatus.SIGN_REQUESTED);
  }

  @Test
  void anyRejectionDrivesFailedOverExpiry() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.REJECTED, InviteeStatus.EXPIRED));
    assertThat(request.status()).isEqualTo(SignatureStatus.FAILED);
  }

  @Test
  void anyExpiryWithoutRejectionDrivesExpired() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.EXPIRED));
    assertThat(request.status()).isEqualTo(SignatureStatus.EXPIRED);
  }

  @Test
  void perInviteeStatusIsPersistedByCorrelation() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.PENDING));
    SigningRequestInvitee inv0 =
        request.invitees().stream()
            .filter(i -> "INV-0".equals(i.providerInviteeId()))
            .findFirst()
            .orElseThrow();
    assertThat(inv0.status()).isEqualTo(InviteeStatus.SIGNED);
  }

  @Test
  void redundantTerminalApplicationIsIdempotent() {
    SigningRequest request = twoInviteeRequest();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.SIGNED));
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.SIGNED));
    assertThat(request.status()).isEqualTo(SignatureStatus.SIGNED);
  }

  @Test
  void needsArtifactsTrueOnceSignedUntilKeysStored() {
    SigningRequest request = twoInviteeRequest();
    assertThat(request.needsArtifacts()).isFalse();
    request.applyInviteeStatuses(view(InviteeStatus.SIGNED, InviteeStatus.SIGNED));
    assertThat(request.needsArtifacts()).isTrue();
    request.storeArtifactKeys("signed/x.pdf", "audit/x");
    assertThat(request.needsArtifacts()).isFalse();
    assertThat(request.signedPdfKey()).isEqualTo("signed/x.pdf");
  }
}

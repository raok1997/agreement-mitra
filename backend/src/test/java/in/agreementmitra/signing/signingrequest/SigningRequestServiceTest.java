package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SigningRequestService} orchestration — mocked collaborators, no Spring, no
 * I/O. Focus: the persist-before-provider ordering (D9), the shared completion path, and the
 * download-on-SIGNED step (incl. idempotency and ack-and-defer).
 */
@ExtendWith(MockitoExtension.class)
class SigningRequestServiceTest {

  @Mock private AgreementService agreementService;
  @Mock private EsignProvider esignProvider;
  @Mock private SigningRequestPersistence persistence;
  @Mock private BlobStore blobStore;

  private SigningRequestService service() {
    return new SigningRequestService(agreementService, esignProvider, persistence, blobStore);
  }

  private static AgreementResponse agreementWithTwoSigners(UUID id) {
    return new AgreementResponse(
        id,
        "12 MG Road",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        11,
        Instant.now(),
        List.of(
            new SignerResponse(UUID.randomUUID(), "Asha Owner", "asha@example.com", Role.OWNER),
            new SignerResponse(UUID.randomUUID(), "Tara Tenant", "tara@example.com", Role.TENANT)));
  }

  /** Stub the agreement's stored draft so the unsigned PDF can be sourced. */
  private void stubDraft(UUID agreementId) {
    String key = "drafts/" + agreementId + ".pdf";
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.of(key));
    when(blobStore.get(key)).thenReturn("%PDF-1.4 draft".getBytes());
  }

  @Test
  void createPersistsPendingBeforeCallingProviderThenMarksRequested() {
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    when(persistence.createPending(agreementId)).thenReturn(signingRequestId);
    when(esignProvider.createSignRequest(any()))
        .thenReturn(
            new SignSession(
                "DOC-9",
                List.of(
                    new SignSession.InviteeSession(
                        "asha@example.com", "https://sign/a", "2026", "INV-1"),
                    new SignSession.InviteeSession(
                        "tara@example.com", "https://sign/t", "2026", "INV-2"))));

    SigningRequestResponse response = service().create(agreementId);

    assertThat(response.documentId()).isEqualTo("DOC-9");
    assertThat(response.invitees()).hasSize(2);
    assertThat(response.invitees()).allSatisfy(v -> assertThat(v.signerId()).isNotNull());

    // Ordering: persist pending → provider → mark requested.
    InOrder inOrder = Mockito.inOrder(persistence, esignProvider);
    inOrder.verify(persistence).createPending(agreementId);
    inOrder.verify(esignProvider).createSignRequest(any());
    inOrder.verify(persistence).markRequested(eq(signingRequestId), eq("DOC-9"), anyList());
  }

  @Test
  void createForUnknownAgreementThrowsAndTouchesNothing() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(persistence, never()).createPending(any());
    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void providerFailureLeavesPendingRowAndDoesNotMarkRequested() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    when(persistence.createPending(agreementId)).thenReturn(UUID.randomUUID());
    when(esignProvider.createSignRequest(any()))
        .thenThrow(new IllegalStateException("vendor down"));

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOf(IllegalStateException.class);

    verify(persistence).createPending(agreementId);
    verify(persistence, never()).markRequested(any(), any(), anyList());
  }

  @Test
  void createWithoutAnUploadedDraftIsConflictAndTouchesNothing() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.DRAFT_REQUIRED));

    verify(persistence, never()).createPending(any());
    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void createSourcesUnsignedPdfFromTheStoredDraft() {
    UUID agreementId = UUID.randomUUID();
    String key = "drafts/" + agreementId + ".pdf";
    byte[] draftBytes = "%PDF-1.4 the-real-draft".getBytes();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.of(key));
    when(blobStore.get(key)).thenReturn(draftBytes);
    when(persistence.createPending(agreementId)).thenReturn(UUID.randomUUID());
    when(esignProvider.createSignRequest(any()))
        .thenReturn(
            new SignSession(
                "DOC-1",
                List.of(
                    new SignSession.InviteeSession(
                        "asha@example.com", "https://sign/a", "2026", "INV-1"),
                    new SignSession.InviteeSession(
                        "tara@example.com", "https://sign/t", "2026", "INV-2"))));

    service().create(agreementId);

    var captor = org.mockito.ArgumentCaptor.forClass(in.agreementmitra.signing.SignRequest.class);
    verify(esignProvider).createSignRequest(captor.capture());
    assertThat(captor.getValue().unsignedPdf()).isEqualTo(draftBytes);
  }

  @Test
  void webhookWithBadMacIsRejectedWithoutSideEffects() {
    when(esignProvider.verifyWebhook("bad")).thenReturn(Optional.empty());

    assertThat(service().handleWebhook("bad")).isFalse();

    verify(esignProvider, never()).getStatus(any());
    verify(persistence, never()).applyAuthoritativeStatus(any(), any());
  }

  @Test
  void verifiedWebhookDrivesFsmAndDoesNotDownloadWhenNotSigned() {
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.verifyWebhook("ok")).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view)).thenReturn(Optional.empty());

    assertThat(service().handleWebhook("ok")).isTrue();

    verify(persistence).applyAuthoritativeStatus("DOC-7", view);
    verify(esignProvider, never()).download(any());
    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void signedCompletionDownloadsAndStoresArtifactsThenRecordsKeys() {
    UUID signingRequestId = UUID.randomUUID();
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.verifyWebhook("ok")).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view))
        .thenReturn(Optional.of(signingRequestId));
    when(esignProvider.download("DOC-7"))
        .thenReturn(
            new SignedDocument(
                "DOC-7", new byte[] {1}, "application/pdf", new byte[] {2}, "application/xml"));

    assertThat(service().handleWebhook("ok")).isTrue();

    String pdfKey = "signed/" + signingRequestId + ".pdf";
    String auditKey = "audit/" + signingRequestId;
    verify(blobStore).put(eq(pdfKey), any(), eq("application/pdf"));
    verify(blobStore).put(eq(auditKey), any(), eq("application/xml"));
    verify(persistence).storeArtifactKeys(signingRequestId, pdfKey, auditKey);
  }

  @Test
  void detailsApiFailureIsAckedWithoutTransition() {
    when(esignProvider.verifyWebhook("ok")).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenThrow(new RuntimeException("details down"));

    assertThat(service().handleWebhook("ok")).isTrue(); // acked, defer to reconciliation

    verify(persistence, never()).applyAuthoritativeStatus(any(), any());
  }

  @Test
  void downloadFailureIsAckedAndLeavesKeysUnrecordedForReconciliation() {
    UUID signingRequestId = UUID.randomUUID();
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.verifyWebhook("ok")).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view))
        .thenReturn(Optional.of(signingRequestId));
    when(esignProvider.download("DOC-7")).thenThrow(new RuntimeException("download down"));

    assertThat(service().handleWebhook("ok")).isTrue(); // acked, defer to reconciliation

    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).storeArtifactKeys(any(), any(), any());
  }
}

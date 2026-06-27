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
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import in.agreementmitra.signing.stamp.StampProvider;
import in.agreementmitra.signing.stamp.StampResult;
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
  @Mock private StampProvider stampProvider;
  @Mock private SigningRequestPersistence persistence;
  @Mock private BlobStore blobStore;

  private static final byte[] STAMPED_PDF = "%PDF-1.4 stamped".getBytes();

  private SigningRequestService service() {
    return new SigningRequestService(
        agreementService, esignProvider, stampProvider, persistence, blobStore);
  }

  /** Stub a fresh stamp procurement: agreement has no stamp yet → provider composites one. */
  private void stubStamp(UUID agreementId) {
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());
    when(stampProvider.procure(eq(agreementId), any()))
        .thenReturn(new StampResult("BW 0000000001", "KA", 100, false, STAMPED_PDF));
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
    stubStamp(agreementId);
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
    stubStamp(agreementId);
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
  void createStampsTheDraftAndSubmitsTheStampedPdfNotTheRawDraft() {
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = UUID.randomUUID();
    String key = "drafts/" + agreementId + ".pdf";
    byte[] draftBytes = "%PDF-1.4 the-real-draft".getBytes();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.of(key));
    when(blobStore.get(key)).thenReturn(draftBytes);
    stubStamp(agreementId);
    when(persistence.createPending(agreementId)).thenReturn(signingRequestId);
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

    // The raw draft is what gets stamped...
    var procureCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(stampProvider).procure(eq(agreementId), procureCaptor.capture());
    assertThat(procureCaptor.getValue()).isEqualTo(draftBytes);

    // ...the stamped PDF is stored under the agreement-scoped key...
    verify(blobStore)
        .put(eq("stamped/" + agreementId + ".pdf"), eq(STAMPED_PDF), eq("application/pdf"));

    // ...the agreement's stamp data is attached and the request advanced to STAMPED...
    var infoCaptor = org.mockito.ArgumentCaptor.forClass(StampInfo.class);
    verify(persistence).markStamped(eq(signingRequestId), eq(agreementId), infoCaptor.capture());
    StampInfo info = infoCaptor.getValue();
    assertThat(info.serial()).isEqualTo("BW 0000000001");
    assertThat(info.stampedPdfKey()).isEqualTo("stamped/" + agreementId + ".pdf");
    assertThat(info.jurisdiction()).isEqualTo("KA");
    assertThat(info.denomination()).isEqualTo(100);
    assertThat(info.dutyPaid()).isFalse();

    // ...and the provider receives the STAMPED pdf, not the bare draft.
    var captor = org.mockito.ArgumentCaptor.forClass(in.agreementmitra.signing.SignRequest.class);
    verify(esignProvider).createSignRequest(captor.capture());
    assertThat(captor.getValue().unsignedPdf()).isEqualTo(STAMPED_PDF).isNotEqualTo(draftBytes);
  }

  @Test
  void alreadyStampedAgreementIsReusedNotReStamped() {
    // Forward-looking / dormant under v1 lock-forever: constructed populated-stampInfo state.
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = UUID.randomUUID();
    String stampedKey = "stamped/" + agreementId + ".pdf";
    byte[] reused = "%PDF-1.4 already-stamped".getBytes();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.of("drafts/x.pdf"));
    when(blobStore.get("drafts/x.pdf")).thenReturn("draft".getBytes());
    when(agreementService.stampInfo(agreementId))
        .thenReturn(
            Optional.of(
                new StampInfo("BW 0000000002", stampedKey, 100, "KA", false, Instant.now())));
    when(blobStore.get(stampedKey)).thenReturn(reused);
    when(persistence.createPending(agreementId)).thenReturn(signingRequestId);
    when(esignProvider.createSignRequest(any()))
        .thenReturn(
            new SignSession(
                "DOC-RU",
                List.of(
                    new SignSession.InviteeSession("asha@example.com", "u", "2026", "INV-1"),
                    new SignSession.InviteeSession("tara@example.com", "u", "2026", "INV-2"))));

    service().create(agreementId);

    verify(stampProvider, never()).procure(any(), any());
    verify(persistence).markStamped(signingRequestId); // reuse overload, no attach
    verify(persistence, never()).markStamped(any(), any(), any());
    verify(blobStore, never()).put(any(), any(), any());
    var captor = org.mockito.ArgumentCaptor.forClass(in.agreementmitra.signing.SignRequest.class);
    verify(esignProvider).createSignRequest(captor.capture());
    assertThat(captor.getValue().unsignedPdf()).isEqualTo(reused);
  }

  @Test
  void unparseableDraftDrivesStampFailedAndNoProviderCall() {
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    when(persistence.createPending(agreementId)).thenReturn(signingRequestId);
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());
    when(stampProvider.procure(eq(agreementId), any()))
        .thenThrow(new in.agreementmitra.StampFailedException("bad pdf"));

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOf(in.agreementmitra.StampFailedException.class);

    verify(persistence).markStampFailed(signingRequestId);
    verify(esignProvider, never()).createSignRequest(any());
    verify(persistence, never()).markRequested(any(), any(), anyList());
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

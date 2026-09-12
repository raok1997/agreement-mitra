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
import in.agreementmitra.signing.WebhookHeaders;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.JurisdictionEligibility;
import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import in.agreementmitra.signing.payment.PaymentGate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Unit tests for {@link SigningRequestService} orchestration - mocked collaborators, no Spring, no
 * I/O. Focus: the stamp precondition (stamping is no longer something this flow can do), the
 * provider ordering (D9), the shared completion path, and the download-on-SIGNED step.
 */
@ExtendWith(MockitoExtension.class)
class SigningRequestServiceTest {

  @Mock private AgreementService agreementService;
  @Mock private EsignProvider esignProvider;
  @Mock private SigningRequestPersistence persistence;
  @Mock private BlobStore blobStore;
  @Mock private PaymentGate paymentGate;

  // Permissive by default (a Mockito mock does nothing), which is what these tests want: they
  // exercise the OTHER preconditions. The jurisdiction gate's own behaviour is covered by
  // JurisdictionEligibilityTest and by the integration tests.
  @Mock private JurisdictionEligibility jurisdiction;

  /**
   * Fulfilment is downstream of the legal record and must never be able to affect it, so it is
   * mocked here and its own behaviour is tested in the delivery tests. What these tests still pin
   * is that completion calls it - and that a completion still succeeds regardless of what it does.
   */
  @Mock private in.agreementmitra.signing.delivery.SignedDocumentDeliveryService deliveryService;

  private static final byte[] STAMPED_PDF = "%PDF-1.4 stamped".getBytes();

  /**
   * A real {@link in.agreementmitra.signing.contact.PartyReachability} with email enabled, not a
   * mock: the contact gate's whole point is the rule it applies, and stubbing it would assert only
   * that the service calls something. Email-only matches the shipped channel configuration.
   */
  private static in.agreementmitra.signing.contact.PartyReachability reachability() {
    return new in.agreementmitra.signing.contact.PartyReachability(
        new in.agreementmitra.signing.contact.DeliveryChannelProperties(
            "http://localhost:5173",
            java.util.Map.of(
                in.agreementmitra.signing.contact.DeliveryChannel.EMAIL,
                new in.agreementmitra.signing.contact.DeliveryChannelProperties.ChannelSettings(
                    true))));
  }

  private SigningRequestService service() {
    return new SigningRequestService(
        agreementService,
        esignProvider,
        persistence,
        blobStore,
        paymentGate,
        jurisdiction,
        deliveryService,
        reachability());
  }

  private static StampInfo attachedStamp(UUID agreementId) {
    return new StampInfo(
        "IN-KA12345678901234X",
        "stamped/" + agreementId + ".pdf",
        "estamp-scans/" + agreementId,
        new BigDecimal("500.00"),
        "KA",
        LocalDate.of(2026, 1, 15),
        "Rental agreement",
        "AgreementMitra Operations",
        true,
        Instant.now());
  }

  /** Stub the agreement's stored draft key (bytes are never read by the signing flow now). */
  private void stubDraft(UUID agreementId) {
    when(agreementService.draftPdfKey(agreementId))
        .thenReturn(Optional.of("drafts/" + agreementId + ".pdf"));
  }

  /** Stub an agreement that staff have already stamped, and its STAMPED signing-request row. */
  private UUID stubStamped(UUID agreementId) {
    StampInfo info = attachedStamp(agreementId);
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.of(info));
    when(blobStore.get(info.stampedPdfKey())).thenReturn(STAMPED_PDF);
    UUID signingRequestId = UUID.randomUUID();
    when(persistence.stampedRequestId(agreementId)).thenReturn(Optional.of(signingRequestId));
    return signingRequestId;
  }

  private static AgreementResponse agreementWithTwoSigners(UUID id) {
    return new AgreementResponse(
        id,
        "AM-TEST-01012026",
        "12 MG Road",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1),
        11,
        Instant.now(),
        List.of(
            new SignerResponse(
                UUID.randomUUID(),
                "Asha Owner",
                "Asha",
                "Owner",
                "Ravi Owner",
                "1 A St",
                "asha@example.com",
                null,
                Role.OWNER),
            new SignerResponse(
                UUID.randomUUID(),
                "Tara Tenant",
                "Tara",
                "Tenant",
                "Hari Tenant",
                "3 C St",
                "tara@example.com",
                null,
                Role.TENANT)));
  }

  private static AgreementResponse agreementWithContactlessTenant(UUID id) {
    return new AgreementResponse(
        id,
        "AM-TEST-01012026",
        "12 MG Road",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1),
        11,
        Instant.now(),
        List.of(
            new SignerResponse(
                UUID.randomUUID(),
                "Asha Owner",
                "Asha",
                "Owner",
                "Ravi Owner",
                "1 A St",
                "asha@example.com",
                null,
                Role.OWNER),
            new SignerResponse(
                UUID.randomUUID(),
                "Tara Tenant",
                "Tara",
                "Tenant",
                "Hari Tenant",
                "3 C St",
                null,
                null,
                Role.TENANT)));
  }

  /**
   * An agreement with an empty signer set -- nothing signable (defensive: creation forbids this).
   */
  private static AgreementResponse agreementWithNoSigners(UUID id) {
    return new AgreementResponse(
        id,
        "AM-TEST-01012026",
        "12 MG Road",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1),
        11,
        Instant.now(),
        List.of());
  }

  @Test
  void createRejectsWith409WhenAPartyHasNoContact() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithContactlessTenant(agreementId)));

    assertThatThrownBy(() -> service().create(agreementId)).isInstanceOf(ConflictException.class);

    // No provider call once the contact check fails.
    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void createCallsProviderThenMarksRequested() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    UUID signingRequestId = stubStamped(agreementId);
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

    // Ordering: provider call, then the SIGN_REQUESTED transition (never before).
    InOrder inOrder = Mockito.inOrder(esignProvider, persistence);
    inOrder.verify(esignProvider).createSignRequest(any());
    inOrder.verify(persistence).markRequested(eq(signingRequestId), eq("DOC-9"), anyList(), any());
  }

  @Test
  void createBindsEachSignerToItsRoleDerivedEsignAnchor() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    stubStamped(agreementId);
    when(esignProvider.createSignRequest(any()))
        .thenReturn(
            new SignSession(
                "DOC-A",
                List.of(
                    new SignSession.InviteeSession("asha@example.com", "u", "2026", "INV-1"),
                    new SignSession.InviteeSession("tara@example.com", "u", "2026", "INV-2"))));

    service().create(agreementId);

    // The provider request binds one esign:<role> anchor per signer, derived from the role, in
    // signer order -- the same tokens the renderer emits at each signature zone.
    var captor = org.mockito.ArgumentCaptor.forClass(in.agreementmitra.signing.SignRequest.class);
    verify(esignProvider).createSignRequest(captor.capture());
    assertThat(captor.getValue().invitees())
        .extracting(in.agreementmitra.signing.SignRequest.Invitee::primaryAnchor)
        .containsExactly("esign:owner", "esign:tenant");
  }

  @Test
  void createGivesEverySignerAnAnchoredBlockAndAnEveryPagePlacement() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    stubStamped(agreementId);
    when(esignProvider.createSignRequest(any()))
        .thenReturn(
            new SignSession(
                "DOC-A",
                List.of(
                    new SignSession.InviteeSession("asha@example.com", "u", "2026", "INV-1"),
                    new SignSession.InviteeSession("tara@example.com", "u", "2026", "INV-2"))));

    service().create(agreementId);

    // Two placements per signer, in order: the anchored signature block first (the primary), then
    // the strip that appears on every page. Order is meaningful -- an adapter that supports only
    // one position takes the first.
    var captor = org.mockito.ArgumentCaptor.forClass(in.agreementmitra.signing.SignRequest.class);
    verify(esignProvider).createSignRequest(captor.capture());
    assertThat(captor.getValue().invitees())
        .allSatisfy(
            invitee ->
                assertThat(invitee.placements())
                    .extracting(in.agreementmitra.signing.SignRequest.Placement::pageScope)
                    .containsExactly(
                        in.agreementmitra.signing.SignRequest.PageScope.ANCHOR_PAGE,
                        in.agreementmitra.signing.SignRequest.PageScope.ALL_PAGES));
    assertThat(captor.getValue().invitees())
        .extracting(i -> i.placements().get(1).anchor())
        .containsOnlyNulls();
  }

  @Test
  void createFailsClearlyWhenThereIsNothingSignableAndSubmitsNothing() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithNoSigners(agreementId)));

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.NOT_SIGNABLE));

    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void createForUnknownAgreementThrowsAndTouchesNothing() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void providerFailureDoesNotMarkRequested() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    stubStamped(agreementId);
    when(esignProvider.createSignRequest(any()))
        .thenThrow(new IllegalStateException("vendor down"));

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOf(IllegalStateException.class);

    verify(persistence, never()).markRequested(any(), any(), anyList(), any());
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

    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void createWithoutAnAttachedStampIsADistinguishable409AndPersistsNothing() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().create(agreementId))
        .isInstanceOfSatisfying(
            ConflictException.class,
            // Distinct from DRAFT_REQUIRED / CONTACT_REQUIRED: a different person fixes each.
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.STAMP_REQUIRED));

    // No signing-request row is created and the provider is never called.
    verify(persistence, never()).markRequested(any(), any(), anyList(), any());
    verify(esignProvider, never()).createSignRequest(any());
  }

  @Test
  void theStampedPdfIsWhatReachesTheProviderNotTheDraft() {
    UUID agreementId = UUID.randomUUID();
    when(agreementService.findById(agreementId))
        .thenReturn(Optional.of(agreementWithTwoSigners(agreementId)));
    stubDraft(agreementId);
    stubStamped(agreementId);
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
    assertThat(captor.getValue().unsignedPdf()).isEqualTo(STAMPED_PDF);
    // Nothing is composited or written at signing time - the stamp is already attached.
    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void webhookWithBadMacIsRejectedWithoutSideEffects() {
    when(esignProvider.parseWebhookTransactionId("bad")).thenReturn(Optional.empty());
    when(esignProvider.verifyWebhook(eq("bad"), any(), any())).thenReturn(Optional.empty());

    assertThat(service().handleWebhook("bad", WebhookHeaders.empty())).isFalse();

    verify(esignProvider, never()).getStatus(any());
    verify(persistence, never()).applyAuthoritativeStatus(any(), any());
  }

  @Test
  void verifiedWebhookDrivesFsmAndDoesNotDownloadWhenNotSigned() {
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.parseWebhookTransactionId("ok")).thenReturn(Optional.of("DOC-7"));
    when(persistence.webhookKeyFor("DOC-7")).thenReturn(Optional.of("KEY"));
    when(esignProvider.verifyWebhook(eq("ok"), any(), eq("KEY"))).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view)).thenReturn(Optional.empty());

    assertThat(service().handleWebhook("ok", WebhookHeaders.empty())).isTrue();

    verify(persistence).applyAuthoritativeStatus("DOC-7", view);
    verify(esignProvider, never()).download(any());
    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void signedCompletionDownloadsAndStoresArtifactsThenRecordsKeys() {
    UUID signingRequestId = UUID.randomUUID();
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.parseWebhookTransactionId("ok")).thenReturn(Optional.of("DOC-7"));
    when(persistence.webhookKeyFor("DOC-7")).thenReturn(Optional.of("KEY"));
    when(esignProvider.verifyWebhook(eq("ok"), any(), eq("KEY"))).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view))
        .thenReturn(Optional.of(signingRequestId));
    when(esignProvider.download("DOC-7"))
        .thenReturn(
            new SignedDocument(
                "DOC-7", new byte[] {1}, "application/pdf", new byte[] {2}, "application/xml"));

    assertThat(service().handleWebhook("ok", WebhookHeaders.empty())).isTrue();

    String pdfKey = "signed/" + signingRequestId + ".pdf";
    String auditKey = "audit/" + signingRequestId;
    verify(blobStore).put(eq(pdfKey), any(), eq("application/pdf"));
    verify(blobStore).put(eq(auditKey), any(), eq("application/xml"));
    verify(persistence).storeArtifactKeys(signingRequestId, pdfKey, auditKey);
  }

  @Test
  void detailsApiFailureIsAckedWithoutTransition() {
    when(esignProvider.parseWebhookTransactionId("ok")).thenReturn(Optional.of("DOC-7"));
    when(persistence.webhookKeyFor("DOC-7")).thenReturn(Optional.of("KEY"));
    when(esignProvider.verifyWebhook(eq("ok"), any(), eq("KEY"))).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenThrow(new RuntimeException("details down"));

    assertThat(service().handleWebhook("ok", WebhookHeaders.empty()))
        .isTrue(); // acked, defer to reconciliation

    verify(persistence, never()).applyAuthoritativeStatus(any(), any());
  }

  @Test
  void downloadFailureIsAckedAndLeavesKeysUnrecordedForReconciliation() {
    UUID signingRequestId = UUID.randomUUID();
    DocumentStatusView view = new DocumentStatusView(List.of());
    when(esignProvider.parseWebhookTransactionId("ok")).thenReturn(Optional.of("DOC-7"));
    when(persistence.webhookKeyFor("DOC-7")).thenReturn(Optional.of("KEY"));
    when(esignProvider.verifyWebhook(eq("ok"), any(), eq("KEY"))).thenReturn(Optional.of("DOC-7"));
    when(esignProvider.getStatus("DOC-7")).thenReturn(view);
    when(persistence.applyAuthoritativeStatus("DOC-7", view))
        .thenReturn(Optional.of(signingRequestId));
    when(esignProvider.download("DOC-7")).thenThrow(new RuntimeException("download down"));

    assertThat(service().handleWebhook("ok", WebhookHeaders.empty()))
        .isTrue(); // acked, defer to reconciliation

    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).storeArtifactKeys(any(), any(), any());
  }
}

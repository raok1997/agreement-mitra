package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.StampFailedException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.agreement.StaffAgreementView;
import in.agreementmitra.signing.agreement.StaffPartyView;
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.StampIntakeResponse;
import in.agreementmitra.signing.api.StampQueueEntry;
import in.agreementmitra.signing.payment.PaymentGate;
import in.agreementmitra.signing.stamp.CertificateScanValidator;
import in.agreementmitra.signing.stamp.StampCertificate;
import in.agreementmitra.signing.stamp.StampProvider;
import in.agreementmitra.signing.stamp.StampResult;
import in.agreementmitra.support.TestImages;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link StampIntakeService}: the FSM transitions it owns, the order in which it
 * refuses things (nothing written before every gate has passed), and the redaction of the
 * certificate number in what it hands back.
 */
@ExtendWith(MockitoExtension.class)
class StampIntakeServiceTest {

  private static final String REFERENCE = "AM7K3QPW9Z4";
  private static final String CERTIFICATE = "IN-KA12345678901234X";

  @Mock private AgreementService agreementService;
  @Mock private StampProvider stampProvider;
  @Mock private SigningRequestPersistence persistence;
  @Mock private BlobStore blobStore;
  @Mock private StampIntakeAuditor auditor;

  /**
   * The payment gate. Mocked as a permissive no-op here, which is exactly OPTIONAL mode - these
   * tests are about stamp intake, and the gate's own two modes are exercised in {@code
   * PaymentGateTest} and the REQUIRED-mode integration test.
   */
  @Mock private PaymentGate paymentGate;

  @Mock private SigningRequestService signingRequestService;

  private final CertificateScanValidator scanValidator = new CertificateScanValidator();

  private StampIntakeService service() {
    return new StampIntakeService(
        agreementService,
        stampProvider,
        scanValidator,
        persistence,
        blobStore,
        auditor,
        paymentGate,
        signingRequestService);
  }

  private static StampIntakeCommand command(byte[] scan) {
    return command(scan, false);
  }

  private static StampIntakeCommand command(byte[] scan, boolean initiateSigning) {
    return new StampIntakeCommand(
        REFERENCE,
        scan,
        "  in-ka12345678901234x  ", // deliberately un-normalised
        LocalDate.of(2026, 1, 15),
        new BigDecimal("500.00"),
        "KA",
        "Rental agreement",
        "AgreementMitra Operations",
        initiateSigning);
  }

  private StaffAgreementView stubResolvedAgreement(UUID agreementId) {
    StaffAgreementView view =
        new StaffAgreementView(
            agreementId, REFERENCE, "Bengaluru", LocalDate.of(2026, 1, 1), null, null, List.of());
    when(agreementService.findByTrackingReference(REFERENCE)).thenReturn(Optional.of(view));
    return view;
  }

  /**
   * An order placed at finalisation and still resting in PDF_GENERATED -- the only state an upload
   * may advance. Returns the signing-request id the intake is expected to transition.
   */
  private UUID stubAwaitingStamp(UUID agreementId) {
    UUID signingRequestId = UUID.randomUUID();
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());
    when(persistence.currentStatus(agreementId))
        .thenReturn(Optional.of(SignatureStatus.PDF_GENERATED));
    when(persistence.awaitingStampRequestId(agreementId)).thenReturn(Optional.of(signingRequestId));
    return signingRequestId;
  }

  private void stubDraft(UUID agreementId) {
    when(agreementService.draftPdfKey(agreementId))
        .thenReturn(Optional.of("drafts/" + agreementId + ".pdf"));
    when(blobStore.get("drafts/" + agreementId + ".pdf")).thenReturn("%PDF-1.4 draft".getBytes());
  }

  private static StampResult successfulAttach() {
    return new StampResult(
        CERTIFICATE, "KA", new BigDecimal("500.00"), true, "%PDF-1.4 stamped".getBytes());
  }

  @Test
  void aSuccessfulUploadDrivesStampedAndPersistsTheCertificate() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    UUID signingRequestId = stubAwaitingStamp(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());

    StampIntakeResponse response = service().attach(staffId, command(TestImages.certificateScan()));

    // The certificate number is NORMALISED before it reaches the provider (and thus the ledger).
    ArgumentCaptor<StampCertificate> certificate = ArgumentCaptor.forClass(StampCertificate.class);
    verify(stampProvider).attach(any(), any(), certificate.capture());
    assertThat(certificate.getValue().certificateNumber()).isEqualTo(CERTIFICATE);

    // The scan is retained as a separate blob alongside the composited instrument.
    verify(blobStore).put(eq("estamp-scans/" + agreementId), any(), eq("image/png"));
    verify(blobStore).put(eq("stamped/" + agreementId + ".pdf"), any(), eq("application/pdf"));

    ArgumentCaptor<StampInfo> info = ArgumentCaptor.forClass(StampInfo.class);
    verify(persistence).markStamped(eq(signingRequestId), eq(agreementId), info.capture());
    assertThat(info.getValue().certificateNumber()).isEqualTo(CERTIFICATE);
    assertThat(info.getValue().dutyPaid()).isTrue();
    assertThat(info.getValue().scanKey()).isEqualTo("estamp-scans/" + agreementId);
    assertThat(info.getValue().attachedAt()).isNotNull();

    // The staff-facing echo carries confirmation context and a REDACTED certificate number.
    assertThat(response.trackingReference()).isEqualTo(REFERENCE);
    assertThat(response.propertyCity()).isEqualTo("Bengaluru");
    assertThat(response.certificateNumberRedacted()).isEqualTo("***234X").doesNotContain("IN-KA");
  }

  // --- optional signing kick-off ---------------------------------------------

  @Test
  void anUploadWithoutTheInstructionStartsNoSigning() {
    // Starting an eSign spends a billable transaction and invites both parties. It must never
    // happen because a field was omitted.
    UUID agreementId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    stubAwaitingStamp(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());

    StampIntakeResponse response =
        service().attach(UUID.randomUUID(), command(TestImages.certificateScan(), false));

    verifyNoInteractions(signingRequestService);
    assertThat(response.signingInitiated()).isFalse();
    assertThat(response.signingNotStartedReason()).isNull();
  }

  @Test
  void anUploadWithTheInstructionStartsSigningAfterTheStampIsAttached() {
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = stubAwaitingStamp(agreementId);
    stubResolvedAgreement(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());

    StampIntakeResponse response =
        service().attach(UUID.randomUUID(), command(TestImages.certificateScan(), true));

    // Order matters: the stamp is durable BEFORE signing is attempted, never the other way round.
    InOrder inOrder = Mockito.inOrder(persistence, signingRequestService);
    inOrder.verify(persistence).markStamped(eq(signingRequestId), eq(agreementId), any());
    inOrder.verify(signingRequestService).create(agreementId);
    assertThat(response.signingInitiated()).isTrue();
    assertThat(response.signingNotStartedReason()).isNull();
  }

  @Test
  void aSigningFailureLeavesTheStampAttachedAndIsReportedAsNotStarted() {
    // The certificate is spent by this point and can never be re-acquired. A failure to start
    // signing must not roll it back, and must not be reported to the operator as a failed upload.
    UUID agreementId = UUID.randomUUID();
    UUID signingRequestId = stubAwaitingStamp(agreementId);
    stubResolvedAgreement(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());
    when(signingRequestService.create(agreementId))
        .thenThrow(new IllegalStateException("provider down"));

    StampIntakeResponse response =
        service().attach(UUID.randomUUID(), command(TestImages.certificateScan(), true));

    verify(persistence).markStamped(eq(signingRequestId), eq(agreementId), any());
    assertThat(response.certificateNumberRedacted()).isEqualTo("***234X");
    assertThat(response.signingInitiated()).isFalse();
    assertThat(response.signingNotStartedReason()).isEqualTo("PROVIDER_UNAVAILABLE");
  }

  @Test
  void aRefusedPreconditionNamesTheGateWithoutQuotingTheRequest() {
    UUID agreementId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    stubAwaitingStamp(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());
    when(signingRequestService.create(agreementId)).thenThrow(ConflictException.contactRequired());

    StampIntakeResponse response =
        service().attach(UUID.randomUUID(), command(TestImages.certificateScan(), true));

    assertThat(response.signingInitiated()).isFalse();
    assertThat(response.signingNotStartedReason()).isEqualTo("PRECONDITION_CONTACT_REQUIRED");
  }

  @Test
  void anUnresolvableReferenceIs404AndNothingIsTouched() {
    UUID staffId = UUID.randomUUID();
    when(agreementService.findByTrackingReference(REFERENCE)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(persistence, never()).markStamped(any(), any(), any());
    verify(blobStore, never()).put(any(), any(), any());
    verify(auditor).record(eq(staffId), eq(null), eq(REFERENCE), any());
  }

  @Test
  void anAlreadyStampedAgreementIs409AndTheExistingStampIsUntouched() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    when(agreementService.stampInfo(agreementId))
        .thenReturn(
            Optional.of(
                new StampInfo(
                    "IN-KA00000000000000A",
                    "stamped/" + agreementId + ".pdf",
                    "estamp-scans/" + agreementId,
                    new BigDecimal("500.00"),
                    "KA",
                    LocalDate.of(2026, 1, 2),
                    null,
                    null,
                    true,
                    Instant.now())));

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.STAMP_ALREADY_ATTACHED));

    verify(stampProvider, never()).attach(any(), any(), any());
    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).markStamped(any(), any(), any());
  }

  @Test
  void aRejectedScanLeavesTheRequestUntouchedSoStaffCanRetry() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    stubAwaitingStamp(agreementId);

    assertThatThrownBy(() -> service().attach(staffId, command("not an image".getBytes())))
        .isInstanceOf(InvalidUploadException.class);

    // No row created, no transition, nothing stored: the request stays in PDF_GENERATED.
    verify(persistence, never()).markStamped(any(), any(), any());
    verify(persistence, never()).markStampFailed(any());
    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void aCompositionFailureDrivesStampFailedAndStoresNothing() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    UUID signingRequestId = stubAwaitingStamp(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any()))
        .thenThrow(new StampFailedException("draft is corrupt"));

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOf(StampFailedException.class);

    verify(persistence).markStampFailed(signingRequestId);
    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).markStamped(any(), any(), any());
    // ...and the agreement closes as ABANDONED. STAMP_FAILED is terminal for signing and there is
    // no signature to speak of, so this order can never complete; leaving it open would leave dead
    // work in the staff queue indistinguishable from work still in progress. The reason recorded is
    // the stamp-specific one, so a report can still tell WHY it was abandoned.
    verify(agreementService)
        .close(agreementId, in.agreementmitra.signing.ClosureReason.ABANDONED_STAMP_FAILED);
  }

  @Test
  void anUploadOntoAClosedAgreementIsRefusedBeforeAnythingIsWritten() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    // CLOSED is terminal: stamping a closed agreement would spend a real, unrecoverable SHCIL
    // certificate on work that is already over.
    org.mockito.Mockito.doThrow(ConflictException.agreementClosed())
        .when(agreementService)
        .requireOpen(agreementId);

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.AGREEMENT_CLOSED);

    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).markStamped(any(), any(), any());
    verify(persistence, never()).markStampFailed(any());
  }

  @Test
  void aDuplicateCertificateSurfacesAsA409FromTheDatabaseConstraint() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    UUID signingRequestId = stubAwaitingStamp(agreementId);
    stubDraft(agreementId);
    when(stampProvider.attach(any(), any(), any())).thenReturn(successfulAttach());
    // The unique index fires at commit; the service must translate, never pre-check.
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("unique violation"))
        .when(persistence)
        .markStamped(eq(signingRequestId), eq(agreementId), any());

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ConflictException.Kind.CERTIFICATE_ALREADY_USED);
              // The refusal must not name the agreement that already spent the certificate.
              assertThat(e.getMessage()).doesNotContain(agreementId.toString());
            });
  }

  @Test
  void aMissingDraftIs409BeforeAnyRowOrBlob() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    stubAwaitingStamp(agreementId);
    when(agreementService.draftPdfKey(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.DRAFT_REQUIRED));

    verify(persistence, never()).markStamped(any(), any(), any());
    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void anUnfinalisedAgreementIsRefusedAndNoOrderIsCreated() {
    // The order is placed when the CUSTOMER finalises. Intake ADVANCES PDF_GENERATED -> STAMPED; it
    // must never bring an order into being on the customer behalf, because that would freeze an
    // agreement the customer never chose to freeze.
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());
    when(persistence.currentStatus(agreementId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.ORDER_NOT_PLACED));

    verify(persistence, never()).placeOrder(any());
    verify(blobStore, never()).put(any(), any(), any());
    verify(persistence, never()).markStamped(any(), any(), any());
  }

  @Test
  void theQueueIsLongestWaitingFirstAndCarriesTheContextNeededToBuyAStamp() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant now = Instant.now();
    when(persistence.awaitingStampQueue(anyInt()))
        .thenReturn(
            List.of(
                new SigningRequestPersistence.AwaitingStamp(first, now.minusSeconds(7200)),
                new SigningRequestPersistence.AwaitingStamp(second, now.minusSeconds(60))));
    when(agreementService.staffViewsByAgreementId(List.of(first, second)))
        .thenReturn(
            Map.of(
                first,
                new StaffAgreementView(
                    first,
                    "AM7K3QPW9Z4",
                    "Bengaluru",
                    LocalDate.of(2026, 1, 1),
                    "Karnataka Residential Rental Agreement",
                    "KA",
                    List.of(
                        new StaffPartyView(Role.OWNER, "Asha Rao", "Krishna Rao"),
                        new StaffPartyView(Role.TENANT, "Bilal Khan", "Imran Khan"))),
                second,
                new StaffAgreementView(
                    second,
                    "AM4M8TQXR5B",
                    "Mysuru",
                    LocalDate.of(2026, 2, 1),
                    "Karnataka Residential Rental Agreement",
                    "KA",
                    List.of())));
    when(agreementService.paymentStatesByAgreementId(List.of(first, second)))
        .thenReturn(Map.of(first, PaymentState.PAID, second, PaymentState.UNPAID));

    List<StampQueueEntry> queue = service().awaitingStampQueue();

    // Order is preserved from the signing side: the longest-waiting order comes first.
    assertThat(queue).extracting(StampQueueEntry::agreementId).containsExactly(first, second);
    assertThat(queue.get(0).waitingSeconds()).isGreaterThanOrEqualTo(7200);
    assertThat(queue.get(0).trackingReference()).isEqualTo("AM7K3QPW9Z4");
    assertThat(queue.get(0).propertyCity()).isEqualTo("Bengaluru");
    // The purchasing context: what the vendor's certificate form asks for.
    assertThat(queue.get(0).templateName()).isEqualTo("Karnataka Residential Rental Agreement");
    assertThat(queue.get(0).templateState()).isEqualTo("KA");
    assertThat(queue.get(0).parties())
        .extracting(
            StampQueueEntry.StampPartyEntry::role,
            StampQueueEntry.StampPartyEntry::name,
            StampQueueEntry.StampPartyEntry::fatherName)
        .containsExactly(
            tuple("OWNER", "Asha Rao", "Krishna Rao"), tuple("TENANT", "Bilal Khan", "Imran Khan"));
    // Payment state is now real (the gate landed). In OPTIONAL mode an unpaid order still reaches
    // this queue, so the console has to be able to show which ones nobody has paid for.
    assertThat(queue.get(0).paymentState()).isEqualTo("PAID");
    assertThat(queue.get(1).paymentState()).isEqualTo("UNPAID");
  }

  @Test
  void anAgreementWithNoRecordedPaymentStateShowsAsUnpaidRatherThanBlank() {
    UUID agreementId = UUID.randomUUID();
    Instant now = Instant.now();
    when(persistence.awaitingStampQueue(anyInt()))
        .thenReturn(
            List.of(
                new SigningRequestPersistence.AwaitingStamp(agreementId, now.minusSeconds(10))));
    when(agreementService.staffViewsByAgreementId(List.of(agreementId)))
        .thenReturn(
            Map.of(
                agreementId,
                new StaffAgreementView(
                    agreementId,
                    "AM7K3QPW9Z4",
                    "Bengaluru",
                    LocalDate.of(2026, 1, 1),
                    null,
                    null,
                    List.of())));
    when(agreementService.paymentStatesByAgreementId(List.of(agreementId))).thenReturn(Map.of());

    assertThat(service().awaitingStampQueue().get(0).paymentState()).isEqualTo("UNPAID");
  }

  @Test
  void aRequestPastPdfGeneratedIsRefused() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    when(agreementService.stampInfo(agreementId)).thenReturn(Optional.empty());
    when(persistence.currentStatus(agreementId))
        .thenReturn(Optional.of(SignatureStatus.STAMP_FAILED));

    assertThatThrownBy(() -> service().attach(staffId, command(TestImages.certificateScan())))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.STAMP_ALREADY_ATTACHED));
  }

  @Test
  void everyAttemptIsAuditedIncludingRejections() {
    UUID agreementId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    stubResolvedAgreement(agreementId);
    stubAwaitingStamp(agreementId);

    assertThatThrownBy(() -> service().attach(staffId, command("not an image".getBytes())))
        .isInstanceOf(InvalidUploadException.class);

    verify(auditor).record(eq(staffId), eq(agreementId), eq(REFERENCE), any());
  }

  @Test
  void theCommandNeverRendersSubmittedMetadataInItsToString() {
    StampIntakeCommand command = command(TestImages.certificateScan());
    assertThat(command.toString())
        .doesNotContain("AgreementMitra Operations")
        .doesNotContain("Rental agreement")
        .doesNotContain("in-ka12345678901234x")
        .doesNotContain(REFERENCE);
  }
}

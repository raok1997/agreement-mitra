package in.agreementmitra.signing.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.DeliveryArtifact;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.SigningCompletionView;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.mail.AttachmentCeiling;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the delivery orchestration - mocked collaborators, no Spring, no I/O, no mailbox.
 *
 * <p>What these pin down, in order of how much damage the alternative would do:
 *
 * <ol>
 *   <li>a recipient whose record is already claimed is <b>not</b> sent to - the guard against
 *       emailing a legal document twice when the completion path is re-entered;
 *   <li>a party with no signing-verified address is recorded as unresolvable rather than having a
 *       draft-time address substituted;
 *   <li>an oversize document falls back to a notification, never a truncated attachment;
 *   <li>only the signed agreement is ever attached - never the audit trail;
 *   <li>transient failures retry to the bound and then escalate; permanent ones escalate at once;
 *   <li>closure happens only when every recipient has actually received it.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignedDocumentDeliveryServiceTest {

  private static final String PDF_KEY = "signed/req.pdf";
  private static final byte[] SIGNED_PDF = "PDFBYTES".getBytes();

  @Mock private SigningRequestQuery signingRequestQuery;
  @Mock private AgreementService agreementService;
  @Mock private DeliveryPersistence persistence;
  @Mock private BlobStore blobStore;
  @Mock private EmailSender emailSender;
  @Mock private AttachmentCeiling attachmentCeiling;

  private final UUID agreementId = UUID.randomUUID();
  private final UUID requestId = UUID.randomUUID();
  private final UUID signerId = UUID.randomUUID();

  private SignedDocumentDeliveryService service() {
    return service(5);
  }

  private SignedDocumentDeliveryService service(int maxAttempts) {
    DeliveryProperties properties =
        new DeliveryProperties(
            false,
            Duration.ofMinutes(5),
            maxAttempts,
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            50);
    return new SignedDocumentDeliveryService(
        signingRequestQuery,
        agreementService,
        persistence,
        blobStore,
        emailSender,
        attachmentCeiling,
        properties);
  }

  private SigningCompletionView signedView(String invitedEmail, InviteeStatus inviteeStatus) {
    return new SigningCompletionView(
        requestId,
        agreementId,
        SignatureStatus.SIGNED,
        PDF_KEY,
        List.of(new SigningCompletionView.Party(signerId, invitedEmail, inviteeStatus)));
  }

  private SignedDocumentDelivery pendingRow(String email) {
    return SignedDocumentDelivery.pending(
        agreementId, requestId, signerId, DeliveryArtifact.SIGNED_AGREEMENT, email);
  }

  private void stubDeliverableRow(SignedDocumentDelivery row) {
    when(persistence.exists(requestId, signerId, DeliveryArtifact.SIGNED_AGREEMENT))
        .thenReturn(true);
    when(persistence.forSigningRequest(requestId)).thenReturn(List.of(row));
    when(blobStore.get(PDF_KEY)).thenReturn(SIGNED_PDF);
    when(agreementService.findById(agreementId)).thenReturn(java.util.Optional.empty());
  }

  // --- exactly-once -----------------------------------------------------------

  @Test
  void aRecipientWhoseRecordIsAlreadyClaimedIsNotSentTo() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    // Somebody else won the guarded transition - a re-delivered webhook, the reconciliation job, or
    // a concurrent completion. This attempt must send nothing at all.
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(false);

    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    verify(emailSender, never()).send(any());
    verify(persistence, never()).markSent(any(), anyBoolean());
    // ...and it does not even read the document: everything after the claim belongs to the winner.
    verify(blobStore, never()).get(any());
  }

  @Test
  void aClaimedRecipientIsSentToExactlyOnceAndMarkedSent() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(false);

    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    verify(emailSender).send(any());
    verify(persistence).markSent(row.getId(), false);
  }

  // --- recipient resolution ---------------------------------------------------

  @Test
  void aPartyWithNoVerifiedAddressIsRecordedUnresolvableAndNothingIsSent() {
    when(persistence.exists(requestId, signerId, DeliveryArtifact.SIGNED_AGREEMENT))
        .thenReturn(false);
    when(persistence.forSigningRequest(requestId)).thenReturn(List.of());

    // Invited at an address, but that party never completed signing there. No fallback exists.
    service().fulfil(signedView("typo@example.com", InviteeStatus.PENDING));

    ArgumentCaptor<SignedDocumentDelivery> created =
        ArgumentCaptor.forClass(SignedDocumentDelivery.class);
    verify(persistence).createIfAbsent(created.capture());
    assertThat(created.getValue().status())
        .isEqualTo(in.agreementmitra.signing.DeliveryStatus.UNRESOLVABLE);
    // Critically: the record carries NO address. The draft-time one is never substituted.
    assertThat(created.getValue().recipientEmail()).isNull();
    verify(emailSender, never()).send(any());
    verify(agreementService, never()).close(any(), any());
  }

  // --- oversize fallback + audit trail ----------------------------------------

  @Test
  void anOversizeDocumentFallsBackToANotificationAndNeverATruncatedAttachment() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(true);

    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailSender).send(sent.capture());
    assertThat(sent.getValue().hasAttachment()).isFalse();
    assertThat(sent.getValue().body()).contains("AgreementMitra account");
    // Recorded as a notification, so "we emailed them the agreement" and "we told them where to
    // fetch it" stay distinguishable in the record.
    verify(persistence).markSent(row.getId(), true);
  }

  @Test
  void theOnlyThingEverAttachedIsTheSignedAgreement() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(false);

    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailSender).send(sent.capture());
    EmailMessage message = sent.getValue();
    assertThat(message.attachment().content()).isEqualTo(SIGNED_PDF);
    assertThat(message.attachment().contentType()).isEqualTo("application/pdf");
    // The audit trail carries eKYC-derived detail and is never attached, named, or linked.
    assertThat(message.attachment().filename()).doesNotContain("audit");
    assertThat(message.body().toLowerCase(java.util.Locale.ROOT)).doesNotContain("audit");
    // Exactly one attachment, and only the signed-document key was ever read from storage.
    verify(blobStore).get(PDF_KEY);
    verify(blobStore, never()).get(org.mockito.ArgumentMatchers.contains("audit"));
  }

  // --- retry classification ---------------------------------------------------

  @Test
  void aPermanentFailureStopsImmediatelyAndEscalates() {
    SignedDocumentDelivery row = pendingRow("nobody@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(false);
    org.mockito.Mockito.doThrow(
            EmailDeliveryException.permanentFailure("recipient-address-rejected", null))
        .when(emailSender)
        .send(any());

    service().fulfil(signedView("nobody@example.com", InviteeStatus.SIGNED));

    verify(persistence).markFailed(row.getId(), "recipient-address-rejected");
    verify(persistence, never()).markRetryable(any(), any(), any());
    // A delivery failure never closes the agreement, and never touches the signing record.
    verify(agreementService, never()).close(any(), any());
  }

  @Test
  void aTransientFailureIsScheduledForRetryWithBackoff() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(false);
    org.mockito.Mockito.doThrow(
            EmailDeliveryException.transientFailure("provider-unavailable", null))
        .when(emailSender)
        .send(any());

    Instant before = Instant.now();
    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
    verify(persistence)
        .markRetryable(eq(row.getId()), eq("provider-unavailable"), nextAttempt.capture());
    assertThat(nextAttempt.getValue()).isAfter(before);
    verify(persistence, never()).markFailed(any(), any());
  }

  @Test
  void aTransientFailureOnTheLastPermittedAttemptEscalatesInsteadOfRetryingForever() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    stubDeliverableRow(row);
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(attachmentCeiling.exceededBy(SIGNED_PDF.length)).thenReturn(false);
    org.mockito.Mockito.doThrow(
            EmailDeliveryException.transientFailure("provider-unavailable", null))
        .when(emailSender)
        .send(any());

    // maxAttempts = 1, so the claim's increment makes this attempt the last one permitted.
    service(1).fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    verify(persistence).markFailed(row.getId(), "retry-limit-reached");
    verify(persistence, never()).markRetryable(any(), any(), any());
  }

  @Test
  void anUnreadableSignedDocumentIsTreatedAsRetryableRatherThanStranded() {
    SignedDocumentDelivery row = pendingRow("asha@example.com");
    when(persistence.exists(requestId, signerId, DeliveryArtifact.SIGNED_AGREEMENT))
        .thenReturn(true);
    when(persistence.forSigningRequest(requestId)).thenReturn(List.of(row));
    when(agreementService.findById(agreementId)).thenReturn(java.util.Optional.empty());
    when(persistence.claim(eq(row.getId()), any(Instant.class))).thenReturn(true);
    when(blobStore.get(PDF_KEY)).thenThrow(new IllegalStateException("storage down"));

    service().fulfil(signedView("asha@example.com", InviteeStatus.SIGNED));

    verify(emailSender, never()).send(any());
    verify(persistence)
        .markRetryable(eq(row.getId()), eq("signed-document-unavailable"), any(Instant.class));
  }

  // --- closure ----------------------------------------------------------------

  @Test
  void terminalSigningFailuresCloseAsAbandonedWithTheirOwnReason() {
    when(signingRequestQuery.completionViewFor("DOC")).thenReturn(java.util.Optional.empty());

    service()
        .fulfil(
            new SigningCompletionView(
                requestId, agreementId, SignatureStatus.FAILED, null, List.of()));
    verify(agreementService).close(agreementId, ClosureReason.ABANDONED_SIGNING_FAILED);

    service()
        .fulfil(
            new SigningCompletionView(
                requestId, agreementId, SignatureStatus.EXPIRED, null, List.of()));
    verify(agreementService).close(agreementId, ClosureReason.ABANDONED_SIGNING_EXPIRED);

    // ...and no email is sent for a signing that never produced a signed agreement.
    verify(emailSender, never()).send(any());
  }

  @Test
  void anAgreementIsNotClosedWhileAnyRecipientIsStillOutstanding() {
    SignedDocumentDelivery delivered = pendingRow("asha@example.com");
    SignedDocumentDelivery stuck =
        SignedDocumentDelivery.unresolvable(
            agreementId, requestId, UUID.randomUUID(), DeliveryArtifact.SIGNED_AGREEMENT);
    when(persistence.exists(any(), any(), any())).thenReturn(true);
    when(persistence.forSigningRequest(requestId)).thenReturn(List.of(delivered, stuck));
    when(persistence.claim(any(), any(Instant.class))).thenReturn(false);
    when(agreementService.findById(agreementId)).thenReturn(java.util.Optional.empty());

    service()
        .fulfil(
            new SigningCompletionView(
                requestId,
                agreementId,
                SignatureStatus.SIGNED,
                PDF_KEY,
                List.of(
                    new SigningCompletionView.Party(
                        signerId, "asha@example.com", InviteeStatus.SIGNED),
                    new SigningCompletionView.Party(
                        stuck.signerId(), null, InviteeStatus.SIGNED))));

    // One party has it, the other never will without help. That is outstanding work, so the
    // agreement stays open - closing here would hide exactly the case somebody has to fix.
    verify(agreementService, never()).close(any(), any());
  }

  @Test
  void deliveryFailureNeverPropagatesOutOfTheCompletionPath() {
    // A signature is a legal fact that has already happened. If the fulfilment step blew up here it
    // would look like - or cause - a completion failure.
    when(signingRequestQuery.completionViewFor("DOC"))
        .thenThrow(new IllegalStateException("database down"));

    service().onSigningCompleted("DOC");
  }
}

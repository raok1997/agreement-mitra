package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.support.LogCapture;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link DraftService} — mocked collaborators, no Spring, no I/O. Covers magic-byte
 * validation, the owner gate, the freeze rule, the side-effect order (a rejected upload must never
 * touch storage), and the storage key derivation.
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

  @Mock private AgreementRepository repository;
  @Mock private BlobStore blobStore;
  @Mock private SigningRequestQuery signingRequestQuery;
  @Mock private PaymentOrderQuery paymentOrderQuery;
  @Mock private AgreementDeletionRepository deletions;
  @Mock private Agreement agreement;

  @RegisterExtension final LogCapture logs = LogCapture.of(DraftService.class, Level.DEBUG);

  private DraftService service() {
    return new DraftService(
        repository, blobStore, signingRequestQuery, paymentOrderQuery, deletions);
  }

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static final UUID CALLER = UUID.randomUUID();

  /** The agreement loads under the write lock and admits {@link #CALLER}. */
  private void admitted(UUID id) {
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(agreement));
    when(agreement.admits(CALLER)).thenReturn(true);
  }

  private static byte[] validPdf() {
    return "%PDF-1.4\n...".getBytes();
  }

  @Test
  void validPdfIsStoredUnderUuidDerivedKeyAndAttached() {
    UUID id = UUID.randomUUID();
    byte[] bytes = validPdf();
    admitted(id);
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(false);

    service().attachDraft(id, CALLER, bytes);

    String expectedKey = "drafts/" + id + ".pdf";
    verify(blobStore).put(eq(expectedKey), eq(bytes), eq("application/pdf"));
    verify(agreement).attachDraft(expectedKey);
  }

  @Test
  void unknownAgreementIsNotFoundAndStoresNothing() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().attachDraft(id, CALLER, validPdf()))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(blobStore, signingRequestQuery);
  }

  @Test
  void nonPdfContentIsRejectedAndStoresNothing() {
    UUID id = UUID.randomUUID();
    admitted(id);

    assertThatThrownBy(() -> service().attachDraft(id, CALLER, "<html>not a pdf</html>".getBytes()))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
    verify(agreement, never()).attachDraft(any());
  }

  @Test
  void emptyUploadIsRejected() {
    UUID id = UUID.randomUUID();
    admitted(id);

    assertThatThrownBy(() -> service().attachDraft(id, CALLER, new byte[0]))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void subSignatureUploadIsRejectedWithoutError() {
    UUID id = UUID.randomUUID();
    admitted(id);

    // 1–4 bytes — shorter than "%PDF-"; must be a clean 400, not an IndexOutOfBounds.
    assertThatThrownBy(() -> service().attachDraft(id, CALLER, new byte[] {'%', 'P', 'D'}))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void uploadIsFrozenOnceASigningRequestExists() {
    UUID id = UUID.randomUUID();
    admitted(id);
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(true);

    assertThatThrownBy(() -> service().attachDraft(id, CALLER, validPdf()))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.DRAFT_FROZEN));

    // Frozen — the blob is never overwritten and the key is never re-attached.
    verify(blobStore, never()).put(any(), any(), any());
    verify(agreement, never()).attachDraft(any());
  }

  @Test
  void aNonOwnerIsNotFoundBeforeAnyContentCheckAndStoresNothing() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(agreement));
    when(agreement.admits(CALLER)).thenReturn(false);

    // A non-PDF body: the owner check answers first, so the refusal is the unknown-id 404.
    assertThatThrownBy(() -> service().attachDraft(id, CALLER, "<html/>".getBytes()))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(repository, never()).findById(any());
    verifyNoInteractions(blobStore, signingRequestQuery);
    verify(agreement, never()).attachDraft(any());
  }

  // --- deleteDraft -------------------------------------------------------------------------

  /** The agreement loads under the write lock, owned by {@link #CALLER}, and the rule accepts. */
  private void owned(UUID id) {
    when(repository.findByIdForDelete(id)).thenReturn(Optional.of(agreement));
    when(agreement.ownerIdentityId()).thenReturn(CALLER);
  }

  private void deletable(UUID id) {
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(false);
    when(paymentOrderQuery.existsForAgreement(id)).thenReturn(false);
    when(agreement.isDeletableDraft(false, false)).thenReturn(true);
    when(agreement.getId()).thenReturn(id);
    when(agreement.trackingReference()).thenReturn("AM7K2Q9XP");
  }

  private static void runAfterCommit() {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
  }

  @Test
  void ownerDeleteRemovesTheAgreementRecordsItAndRemovesObjectsOnlyAfterCommit() {
    UUID id = UUID.randomUUID();
    owned(id);
    deletable(id);
    TransactionSynchronizationManager.initSynchronization();

    service().deleteDraft(id, CALLER);

    verify(repository).delete(agreement);
    ArgumentCaptor<AgreementDeletion> record = ArgumentCaptor.forClass(AgreementDeletion.class);
    verify(deletions).save(record.capture());
    assertThat(record.getValue().getId()).isEqualTo(id);
    assertThat(record.getValue().trackingReference()).isEqualTo("AM7K2Q9XP");
    assertThat(record.getValue().ownerIdentityId()).isEqualTo(CALLER);
    assertThat(record.getValue().reason()).isEqualTo(DeletionReason.OWNER_DELETE);
    assertThat(record.getValue().deletedAt()).isNotNull();
    verifyNoInteractions(blobStore);

    runAfterCommit();

    assertThat(DraftService.draftStageKeys(id)).containsExactly("drafts/" + id + ".pdf");
    DraftService.draftStageKeys(id).forEach(key -> verify(blobStore).delete(key));
  }

  @Test
  void withoutSynchronizationTheObjectsAreRemovedInlineWithAWarning() {
    UUID id = UUID.randomUUID();
    owned(id);
    deletable(id);

    service().deleteDraft(id, CALLER);

    verify(blobStore).delete("drafts/" + id + ".pdf");
    assertThat(logs.hasLevel(Level.WARN)).isTrue();
  }

  @Test
  void anotherOwnersAgreementIsNotFoundAndNothingIsTouched() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForDelete(id)).thenReturn(Optional.of(agreement));
    when(agreement.ownerIdentityId()).thenReturn(UUID.randomUUID());

    assertNotFoundAndUntouched(id, CALLER);
  }

  @Test
  void anUnownedAgreementIsNotFoundAndNothingIsTouched() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForDelete(id)).thenReturn(Optional.of(agreement));
    when(agreement.ownerIdentityId()).thenReturn(null);

    assertNotFoundAndUntouched(id, CALLER);
  }

  @Test
  void anUnknownAgreementIsNotFoundAndNothingIsTouched() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForDelete(id)).thenReturn(Optional.empty());

    assertNotFoundAndUntouched(id, CALLER);
  }

  @Test
  void aNullCallerIsNotFoundEvenForAnUnownedAgreement() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForDelete(id)).thenReturn(Optional.of(agreement));

    assertNotFoundAndUntouched(id, null);
  }

  private void assertNotFoundAndUntouched(UUID id, UUID caller) {
    assertThatThrownBy(() -> service().deleteDraft(id, caller))
        .isInstanceOf(ResourceNotFoundException.class);
    verifyNoInteractions(signingRequestQuery, paymentOrderQuery, deletions, blobStore);
    verify(repository, never()).delete(any());
  }

  @Test
  void anAgreementTheRuleRefusesIsAConflictAndNothingIsTouched() {
    UUID id = UUID.randomUUID();
    owned(id);
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(true);
    when(paymentOrderQuery.existsForAgreement(id)).thenReturn(false);
    when(agreement.isDeletableDraft(true, false)).thenReturn(false);
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> service().deleteDraft(id, CALLER))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.DRAFT_NOT_DELETABLE));

    verify(repository, never()).delete(any());
    verifyNoInteractions(deletions, blobStore);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  @Test
  void aFailedObjectRemovalIsSwallowedAndLoggedWithoutTheThrowable() {
    UUID id = UUID.randomUUID();
    owned(id);
    deletable(id);
    doThrow(
            new IllegalStateException(
                "Failed to delete object", new java.io.IOException("drafts/" + id + ".pdf")))
        .when(blobStore)
        .delete(any());
    TransactionSynchronizationManager.initSynchronization();

    service().deleteDraft(id, CALLER);
    runAfterCommit();

    assertThat(logs.messages())
        .anySatisfy(m -> assertThat(m).contains("IOException").doesNotContain(id.toString()));
    assertThat(logs.throwableMessages()).isEmpty();
  }

  // --- purgeIfStale ------------------------------------------------------------------------

  private static final Instant CUTOFF = Instant.parse("2026-07-01T00:00:00Z");

  /** The agreement loads under the skip-locked lock, last edited at {@code editedAt}. */
  private void lockedForPurge(UUID id, Instant editedAt) {
    when(repository.findByIdForPurge(id)).thenReturn(Optional.of(agreement));
    when(agreement.lastEditedAt()).thenReturn(editedAt);
  }

  @Test
  void aStaleUnclaimedDraftIsPurgedWithARetentionRecordAndNoOwner() {
    UUID id = UUID.randomUUID();
    lockedForPurge(id, CUTOFF.minusSeconds(86_400));
    deletable(id);
    TransactionSynchronizationManager.initSynchronization();

    assertThat(service().purgeIfStale(id, CUTOFF)).isTrue();

    verify(repository).delete(agreement);
    ArgumentCaptor<AgreementDeletion> record = ArgumentCaptor.forClass(AgreementDeletion.class);
    verify(deletions).save(record.capture());
    assertThat(record.getValue().getId()).isEqualTo(id);
    assertThat(record.getValue().trackingReference()).isEqualTo("AM7K2Q9XP");
    assertThat(record.getValue().ownerIdentityId()).isNull();
    assertThat(record.getValue().reason()).isEqualTo(DeletionReason.RETENTION_PURGE);
    verifyNoInteractions(blobStore);

    runAfterCommit();

    verify(blobStore).delete("drafts/" + id + ".pdf");
  }

  @Test
  void aDraftLastEditedOneMicrosecondBeforeTheCutoffIsPurged() {
    UUID id = UUID.randomUUID();
    lockedForPurge(id, CUTOFF.minusNanos(1_000));
    deletable(id);

    assertThat(service().purgeIfStale(id, CUTOFF)).isTrue();
    verify(repository).delete(agreement);
  }

  @Test
  void aDraftLastEditedExactlyAtTheCutoffIsKept() {
    UUID id = UUID.randomUUID();
    lockedForPurge(id, CUTOFF);

    assertThat(service().purgeIfStale(id, CUTOFF)).isFalse();
    assertNothingRemoved();
  }

  @Test
  void aDraftEditedAfterTheCutoffIsKept() {
    UUID id = UUID.randomUUID();
    lockedForPurge(id, CUTOFF.plusSeconds(60));

    assertThat(service().purgeIfStale(id, CUTOFF)).isFalse();
    assertNothingRemoved();
  }

  @Test
  void aMissingOrSkipLockedRowIsKeptWithoutAnyCheck() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForPurge(id)).thenReturn(Optional.empty());

    assertThat(service().purgeIfStale(id, CUTOFF)).isFalse();
    verifyNoInteractions(signingRequestQuery, paymentOrderQuery);
    assertNothingRemoved();
  }

  @Test
  void aStaleAgreementTheRuleRefusesIsKept() {
    UUID id = UUID.randomUUID();
    lockedForPurge(id, CUTOFF.minusSeconds(86_400));
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(false);
    when(paymentOrderQuery.existsForAgreement(id)).thenReturn(true);
    when(agreement.isDeletableDraft(false, true)).thenReturn(false);
    TransactionSynchronizationManager.initSynchronization();

    assertThat(service().purgeIfStale(id, CUTOFF)).isFalse();
    assertNothingRemoved();
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  private void assertNothingRemoved() {
    verify(repository, never()).delete(any());
    verifyNoInteractions(deletions, blobStore);
  }

  // --- draftIdOf ---------------------------------------------------------------------------

  @Test
  void draftIdOfAcceptsExactlyADraftStageKey() {
    UUID id = UUID.randomUUID();

    assertThat(DraftService.draftIdOf("drafts/" + id + ".pdf")).contains(id);
    assertThat(DraftService.draftStagePrefixes()).containsExactly("drafts/");
  }

  @Test
  void draftIdOfRejectsEveryOtherForm() {
    UUID id = UUID.randomUUID();

    assertThat(DraftService.draftIdOf("drafts/readme.txt")).isEmpty();
    assertThat(DraftService.draftIdOf("drafts/" + id + ".png")).isEmpty();
    assertThat(DraftService.draftIdOf("drafts/" + id + ".pdf.bak")).isEmpty();
    assertThat(DraftService.draftIdOf("drafts/" + id.toString().toUpperCase() + ".pdf")).isEmpty();
    assertThat(DraftService.draftIdOf("drafts/sub/" + id + ".pdf")).isEmpty();
    assertThat(DraftService.draftIdOf("signed/" + id + ".pdf")).isEmpty();
    assertThat(DraftService.draftIdOf("drafts/")).isEmpty();
  }
}

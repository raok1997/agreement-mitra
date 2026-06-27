package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.SigningRequestQuery;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DraftService} — mocked collaborators, no Spring, no I/O. Covers magic-byte
 * validation, the freeze rule, the side-effect order (a rejected upload must never touch storage),
 * and the storage key derivation.
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

  @Mock private AgreementRepository repository;
  @Mock private BlobStore blobStore;
  @Mock private SigningRequestQuery signingRequestQuery;
  @Mock private Agreement agreement;

  private DraftService service() {
    return new DraftService(repository, blobStore, signingRequestQuery);
  }

  private static byte[] validPdf() {
    return "%PDF-1.4\n...".getBytes();
  }

  @Test
  void validPdfIsStoredUnderUuidDerivedKeyAndAttached() {
    UUID id = UUID.randomUUID();
    byte[] bytes = validPdf();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(false);

    service().attachDraft(id, bytes);

    String expectedKey = "drafts/" + id + ".pdf";
    verify(blobStore).put(eq(expectedKey), eq(bytes), eq("application/pdf"));
    verify(agreement).attachDraft(expectedKey);
  }

  @Test
  void unknownAgreementIsNotFoundAndStoresNothing() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().attachDraft(id, validPdf()))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(blobStore, signingRequestQuery);
  }

  @Test
  void nonPdfContentIsRejectedAndStoresNothing() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));

    assertThatThrownBy(() -> service().attachDraft(id, "<html>not a pdf</html>".getBytes()))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
    verify(agreement, never()).attachDraft(any());
  }

  @Test
  void emptyUploadIsRejected() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));

    assertThatThrownBy(() -> service().attachDraft(id, new byte[0]))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void subSignatureUploadIsRejectedWithoutError() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));

    // 1–4 bytes — shorter than "%PDF-"; must be a clean 400, not an IndexOutOfBounds.
    assertThatThrownBy(() -> service().attachDraft(id, new byte[] {'%', 'P', 'D'}))
        .isInstanceOf(InvalidUploadException.class);

    verify(blobStore, never()).put(any(), any(), any());
  }

  @Test
  void uploadIsFrozenOnceASigningRequestExists() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(signingRequestQuery.existsForAgreement(id)).thenReturn(true);

    assertThatThrownBy(() -> service().attachDraft(id, validPdf()))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.DRAFT_FROZEN));

    // Frozen — the blob is never overwritten and the key is never re-attached.
    verify(blobStore, never()).put(any(), any(), any());
    verify(agreement, never()).attachDraft(any());
  }
}

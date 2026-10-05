package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.SigningRequestQuery;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exception messages the signing module builds carry only the redacted agreement id
 * (agreement-id-debug-logging 4.2, design D4).
 */
@ExtendWith(MockitoExtension.class)
class AgreementIdExceptionMessageTest {

  private static final UUID ID = UUID.randomUUID();

  @Mock private AgreementRepository repository;
  @Mock private TemplateCatalogApi templateCatalog;
  @Mock private SigningRequestQuery signingRequestQuery;

  @InjectMocks private AgreementService agreementService;

  @Test
  void draftUploadForAnUnknownAgreement() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());
    DraftService drafts = new DraftService(repository, mock(BlobStore.class), signingRequestQuery);

    assertThatThrownBy(() -> drafts.attachDraft(ID, null, "%PDF-1.4\n".getBytes()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(AgreementIds.redact(ID))
        .message()
        .doesNotContain(ID.toString());
  }

  @Test
  void paymentViewForAnUnknownAgreement() {
    when(repository.findById(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> agreementService.paymentView(ID))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(AgreementIds.redact(ID))
        .message()
        .doesNotContain(ID.toString());
  }

  @Test
  void anAgreementThatVanishesBeforeTheStampAttaches() {
    when(repository.findById(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> agreementService.attachStamp(ID, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Agreement vanished: " + AgreementIds.redact(ID))
        .message()
        .doesNotContain(ID.toString());
  }
}

package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.signing.agreement.AgreementDocumentService;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.DraftService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for the generate-as-draft orchestration -- no Spring context, no I/O. Proves the
 * controller renders first, then hands the rendered bytes to the existing draft-store path, then
 * pins the rendered template's identity, and returns the agreement id.
 */
class AgreementControllerTest {

  private final AgreementService agreementService = mock(AgreementService.class);
  private final DraftService draftService = mock(DraftService.class);
  private final AgreementDocumentService documentService = mock(AgreementDocumentService.class);
  private final AgreementController controller =
      new AgreementController(agreementService, draftService, documentService);

  @Test
  void generateDocumentRendersStoresThenPinsAndReturnsId() {
    UUID id = UUID.randomUUID();
    byte[] pdf = "%PDF-1.4 rendered".getBytes(StandardCharsets.UTF_8);
    EffectiveTemplateIdentity identity =
        new EffectiveTemplateIdentity("tmpl-1", "sha256:abc123", Map.of("base", 3));
    when(documentService.renderForDraft(id))
        .thenReturn(new DocumentProjectionResult(pdf, identity));

    ResponseEntity<Map<String, UUID>> resp = controller.generateDocument(id);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("agreementId", id);

    // Render -> store the exact rendered bytes -> pin the rendered identity, strictly in that
    // order.
    InOrder ordered = inOrder(documentService, draftService);
    ordered.verify(documentService).renderForDraft(id);
    ordered.verify(draftService).attachDraft(id, pdf);
    ordered.verify(documentService).pinEffectiveTemplate(id, identity);
    ordered.verifyNoMoreInteractions();
  }
}

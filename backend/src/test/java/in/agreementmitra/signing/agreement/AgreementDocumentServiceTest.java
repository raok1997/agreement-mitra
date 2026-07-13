package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-tests that the render path is DIMENSION-AWARE (agreement-template-selection Gap B), with a
 * mocked repository, document-projection, and catalog (no Spring, no I/O). When the agreement
 * carries a selected template, the service resolves that template's {@code (state, type)} via the
 * public {@link TemplateCatalogApi#detail} seam and passes those dimensions to the projection (so a
 * TG agreement renders the TG overlay); when it carries none, it passes {@code null} dimensions
 * (backward-compatible default) and never touches the catalog.
 */
@ExtendWith(MockitoExtension.class)
class AgreementDocumentServiceTest {

  @Mock private AgreementRepository repository;
  @Mock private in.agreementmitra.documents.api.DocumentProjectionApi documentProjection;
  @Mock private TemplateCatalogApi templateCatalog;

  @InjectMocks private AgreementDocumentService service;

  private static Agreement draft() {
    Agreement a =
        Agreement.create(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-01"));
    a.addSigner("Asha Rao", "Asha", "Rao", "Ravi Rao", "1 A St", null, null, Role.OWNER);
    a.addSigner("Tara Sen", "Tara", "Sen", "Hari Sen", "3 C St", null, null, Role.TENANT);
    return a;
  }

  private static DocumentProjectionResult aResult() {
    return new DocumentProjectionResult(
        new byte[] {'%', 'P', 'D', 'F', '-'},
        new EffectiveTemplateIdentity("rental-base", "hash", Map.of("base", 1)));
  }

  @Test
  void passesTheSelectedTemplateDimensionsToTheProjection() {
    UUID id = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    Agreement agreement = draft();
    agreement.selectTemplate(templateId);
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(templateCatalog.detail(templateId.toString()))
        .thenReturn(
            new TemplateDetail(
                templateId.toString(),
                "TG Residential",
                "desc",
                new TemplateDetail.Dimensions("TG", "residential", "en"),
                1));
    when(documentProjection.generate(any())).thenReturn(aResult());

    service.renderForDraft(id);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    assertThat(req.getValue().dimensions()).isNotNull();
    assertThat(req.getValue().dimensions().state()).isEqualTo("TG");
    assertThat(req.getValue().dimensions().type()).isEqualTo("residential");
  }

  @Test
  void passesNullDimensionsAndSkipsTheCatalogWhenNoTemplateIsSelected() {
    UUID id = UUID.randomUUID();
    Agreement agreement = draft(); // no selectTemplate -> templateId null
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(documentProjection.generate(any())).thenReturn(aResult());

    service.renderPreview(id);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    assertThat(req.getValue().dimensions()).isNull();
    verifyNoInteractions(templateCatalog);
  }
}

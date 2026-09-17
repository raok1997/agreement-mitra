package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.StampRenderUnavailableException;
import in.agreementmitra.documents.DocumentRenderException;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateFormApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
  @Mock private TemplateFormApi templateForms;

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
  void stampsThePersistedTrackingReferenceAsTheFooterReference() {
    // The footer's left-cell label is the agreement's ONE persisted tracking reference -- the same
    // value the customer holds and staff quote at stamp intake. NOT a separately-derived veneer:
    // the old AM-<LAST6>-<DDMMYY> form was computed at render time and was not collision-free.
    UUID id = UUID.randomUUID();
    Agreement agreement = draft();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(documentProjection.generate(any())).thenReturn(aResult());

    service.renderForDraft(id);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    assertThat(req.getValue().documentReference())
        .isEqualTo(agreement.trackingReference())
        .isNotBlank();
    // The retired derived form must not reappear anywhere in the document reference.
    assertThat(req.getValue().documentReference()).doesNotContain("-");
  }

  // --- Render selection (M5, D3): stored capture state feeds data + sections; null -> fallback ---

  @Test
  void rendersFromTheStoredCaptureStateWithFixedColumnsWinning() {
    UUID id = UUID.randomUUID();
    Agreement agreement = draft();
    // The stored map carries a DYNAMIC field plus a STALE fixed-column entry that must NOT win.
    agreement.replaceCaptureState(
        Map.of("lockInMonths", "6", "propertyAddress", "STALE ADDRESS"), List.of("Lock-in"));
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(documentProjection.generate(any())).thenReturn(aResult());

    service.renderForDraft(id);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    // The dynamic field flows through; the added optional section is passed as the sections arg.
    assertThat(req.getValue().data()).containsEntry("lockInMonths", "6");
    assertThat(req.getValue().activeSections()).containsExactly("Lock-in");
    // D3 reconciliation: the authoritative fixed column wins over the stale map entry.
    assertThat(req.getValue().data()).containsEntry("propertyAddress", "12 MG Road, Bengaluru");
  }

  @Test
  void fallsBackToTheFixedColumnMapperWithNoSectionsWhenCaptureStateIsNull() {
    UUID id = UUID.randomUUID();
    Agreement agreement = draft(); // no capture state
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    when(documentProjection.generate(any())).thenReturn(aResult());

    service.renderForDraft(id);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    // Fixed-column mapping, no optional sections (null normalized to empty) -- unchanged behaviour.
    assertThat(req.getValue().data()).containsEntry("propertyAddress", "12 MG Road, Bengaluru");
    assertThat(req.getValue().data()).doesNotContainKey("lockInMonths");
    assertThat(req.getValue().activeSections()).isEmpty();
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

  // --- stamp intake re-render (stamp-duty-amount-from-certificate, design D4/D5) ---

  private static final String PINNED_HASH = "pinned-hash";

  /** A TG agreement whose stored draft is a recorded render of the pinned template. */
  private Agreement renderedTgDraft(UUID id, String capturedAgreementDate) {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = draft();
    agreement.selectTemplate(templateId);
    Map<String, String> capture = new java.util.HashMap<>();
    capture.put("stampDutyAmount", "5000"); // a client-submitted value; documents discards it
    if (capturedAgreementDate != null) {
      capture.put("agreementDate", capturedAgreementDate);
    }
    agreement.replaceCaptureState(capture, List.of());
    agreement.attachDraft("drafts/" + id + ".pdf");
    agreement.pinEffectiveTemplate(
        PINNED_HASH, Map.of("base", 2, "state:TG", 3), LocalDate.parse("2026-09-10"));
    when(repository.findById(id)).thenReturn(Optional.of(agreement));
    org.mockito.Mockito.lenient()
        .when(templateCatalog.detail(templateId.toString()))
        .thenReturn(
            new TemplateDetail(
                templateId.toString(),
                "TG Residential",
                "desc",
                new TemplateDetail.Dimensions("TG", "residential", "en"),
                1));
    return agreement;
  }

  private void currentTemplateHash(String hash) {
    when(templateForms.formFor("TG", "residential"))
        .thenReturn(
            new FormSchema(
                new FormSchema.Dimensions("TG", "residential"), "rental", 1, hash, List.of()));
  }

  private static DocumentProjectionResult resultWithHash(String hash) {
    return new DocumentProjectionResult(
        "%PDF-re-rendered".getBytes(),
        new EffectiveTemplateIdentity("rental", hash, Map.of("base", 2)),
        "2026-09-10");
  }

  @Test
  void reRendersWithTheCertificateDutyAndTheDraftExecutionDate() {
    UUID id = UUID.randomUUID();
    // agreementDate left blank, so the draft printed the date it was rendered on.
    Agreement agreement = renderedTgDraft(id, null);
    currentTemplateHash(PINNED_HASH);
    when(documentProjection.generate(any(), anyMap())).thenReturn(resultWithHash(PINNED_HASH));

    Optional<byte[]> instrument = service.renderForStamp(id, new BigDecimal("100.00"));

    assertThat(instrument).contains("%PDF-re-rendered".getBytes());
    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> system = ArgumentCaptor.forClass(Map.class);
    verify(documentProjection).generate(req.capture(), system.capture());
    // The certificate amount travels ONLY on the server-side system channel.
    assertThat(system.getValue()).containsEntry("stampDutyAmount", new BigDecimal("100.00"));
    // The recorded draft date, never the intake date, and the same tracking reference.
    assertThat(req.getValue().data()).containsEntry("agreementDate", "2026-09-10");
    assertThat(req.getValue().documentReference()).isEqualTo(agreement.trackingReference());
    assertThat(req.getValue().dimensions().state()).isEqualTo("TG");
  }

  @Test
  void aCapturedAgreementDateIsKeptAsIs() {
    UUID id = UUID.randomUUID();
    renderedTgDraft(id, "2026-09-01");
    currentTemplateHash(PINNED_HASH);
    when(documentProjection.generate(any(), anyMap())).thenReturn(resultWithHash(PINNED_HASH));

    service.renderForStamp(id, new BigDecimal("100.00"));

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    verify(documentProjection).generate(req.capture(), anyMap());
    assertThat(req.getValue().data()).containsEntry("agreementDate", "2026-09-01");
  }

  @Test
  void anUploadedDraftIsNotReRendered() {
    UUID id = UUID.randomUUID();
    Agreement agreement = renderedTgDraft(id, null);
    // A later upload replaces the draft but keeps the pin; the cleared date is what tells them
    // apart.
    agreement.attachDraft("drafts/" + id + ".pdf");

    assertThat(service.renderForStamp(id, new BigDecimal("100.00"))).isEmpty();
    verifyNoInteractions(documentProjection, templateForms);
  }

  @Test
  void aDriftedTemplateIsNotReRendered() {
    UUID id = UUID.randomUUID();
    renderedTgDraft(id, null);
    currentTemplateHash("a-newer-hash");

    assertThat(service.renderForStamp(id, new BigDecimal("100.00"))).isEmpty();
    verifyNoInteractions(documentProjection);
  }

  @Test
  void aRenderThatResolvedADifferentTemplateIsDiscarded() {
    UUID id = UUID.randomUUID();
    renderedTgDraft(id, null);
    currentTemplateHash(PINNED_HASH);
    when(documentProjection.generate(any(), anyMap())).thenReturn(resultWithHash("reloaded-hash"));

    assertThat(service.renderForStamp(id, new BigDecimal("100.00"))).isEmpty();
  }

  @Test
  void aRendererOutageIsTranslatedToTheRetryableException() {
    UUID id = UUID.randomUUID();
    renderedTgDraft(id, null);
    currentTemplateHash(PINNED_HASH);
    when(documentProjection.generate(any(), anyMap()))
        .thenThrow(new DocumentRenderException("Gotenberg render failed"));

    assertThatThrownBy(() -> service.renderForStamp(id, new BigDecimal("100.00")))
        .isInstanceOf(StampRenderUnavailableException.class);
  }

  @Test
  void pinningRecordsTheDraftExecutionDateAndStoringADraftClearsIt() {
    UUID id = UUID.randomUUID();
    Agreement agreement = draft();
    when(repository.findById(id)).thenReturn(Optional.of(agreement));

    service.pinEffectiveTemplate(
        id, new EffectiveTemplateIdentity("rental", "h", Map.of("base", 1)), "2026-09-10");
    assertThat(agreement.draftExecutionDate()).isEqualTo(LocalDate.parse("2026-09-10"));

    agreement.attachDraft("drafts/uploaded.pdf");
    assertThat(agreement.draftExecutionDate()).isNull();

    agreement.pinEffectiveTemplate("h", Map.of("base", 1), LocalDate.parse("2026-09-11"));
    agreement.clearDraftPin();
    assertThat(agreement.draftExecutionDate()).isNull();
    verify(documentProjection, never()).generate(any(), anyMap());
  }
}

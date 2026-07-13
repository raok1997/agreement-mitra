package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.HtmlPdfRenderer;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentProjectionService}: resolve -> validate -> compile over the <b>real
 * reference definition</b> (via the resolution engine's {@link ClasspathLayerSource}) and a dummy
 * data map, plus the single-renderer <b>parity</b> guarantee. No Spring context and no Gotenberg --
 * the HTML-to-PDF seam is a capturing stub, so this tier exercises everything up to (and the parity
 * of) the render hand-off without a container.
 */
class DocumentProjectionServiceTest {

  /** Captures the exact HTML handed to the PDF renderer; returns dummy PDF bytes. */
  private static final class CapturingRenderer implements HtmlPdfRenderer {
    private String lastHtml;

    @Override
    public byte[] toPdf(String html) {
      this.lastHtml = html;
      return ("%PDF-" + html.length()).getBytes(StandardCharsets.UTF_8);
    }
  }

  /** A fixed clock so the SYSDATE fallback is deterministic: 2026-07-13 -> "13-Jul-2026". */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(
          LocalDate.of(2026, 7, 13).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private final CapturingRenderer renderer = new CapturingRenderer();
  private final DocumentProjectionService service =
      new DocumentProjectionService(
          new TemplateResolver(new ClasspathLayerSource()),
          new TemplateCompiler(),
          renderer,
          FIXED_CLOCK);

  /**
   * A service over the production {@code sets/rental} set, whose Term section carries the {@code
   * agreementDate} field -- so the resolved execution date is observable in the rendered document.
   * The reference fixture set ({@link ClasspathLayerSource} default) declares no such field.
   */
  private final DocumentProjectionService productionService =
      new DocumentProjectionService(
          new TemplateResolver(new ClasspathLayerSource("documents/template/sets/rental/")),
          new TemplateCompiler(),
          renderer,
          FIXED_CLOCK);

  /**
   * A service over the {@code sets/optional} test set, whose (TG, residential) resolution carries a
   * mandatory Parties section plus an OPTIONAL "Pets" section -- so section gating by {@code
   * activeSections} is observable end-to-end through the two projection faces.
   */
  private final DocumentProjectionService optionalService =
      new DocumentProjectionService(
          new TemplateResolver(new ClasspathLayerSource("documents/template/sets/optional/")),
          new TemplateCompiler(),
          renderer,
          FIXED_CLOCK);

  private static DocumentProjectionRequest inRequest(Map<String, Object> data) {
    return new DocumentProjectionRequest(new DocumentDimensions("IN", "residential"), data);
  }

  private static Map<String, Object> optionalSetData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("tenantName", "Bhaskar Rao");
    data.put("petType", "Cat");
    return data;
  }

  private static DocumentProjectionRequest tgOptionalRequest(List<String> activeSections) {
    return new DocumentProjectionRequest(
        new DocumentDimensions("TG", "residential"), optionalSetData(), activeSections);
  }

  private static DocumentProjectionRequest request(Map<String, Object> data) {
    // Null dimensions -> the service resolves the default (IN, residential).
    return new DocumentProjectionRequest(null, data);
  }

  private static Map<String, Object> fullData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("tenantName", "Bhaskar Rao");
    data.put("monthlyRent", "25000.00");
    data.put("durationMonths", 11);
    data.put("furnished", Boolean.TRUE);
    return data;
  }

  @Test
  void composedHtmlReflectsTheReferenceDefinitionsFieldsClausesAndSections() {
    String html = service.previewHtml(request(fullData()));

    // Sections from the resolved effective template (Financial was replaced by the type layer).
    assertThat(html).contains("<h2>Parties</h2>", "<h2>Financial</h2>", "<h2>Term</h2>");
    // Clause slots filled from the data map.
    assertThat(html)
        .contains("This Agreement is made between Asha Rao (Owner) and Bhaskar Rao (Tenant).")
        .contains("The Tenant shall pay a monthly rent of INR 25000.00.")
        .contains("The tenancy is for a term of 11 month(s).")
        // Clause the residential type layer adds after 'rent'.
        .contains("The premises shall be used for residential purposes only.");
  }

  @Test
  void showWhenClauseIsIncludedWhenTrueAndDroppedWhenFalse() {
    String included = service.previewHtml(request(fullData()));
    assertThat(included).contains("The premises are let furnished, with fixtures as scheduled.");

    Map<String, Object> unfurnished = fullData();
    unfurnished.put("furnished", Boolean.FALSE);
    String dropped = service.previewHtml(request(unfurnished));
    assertThat(dropped).doesNotContain("The premises are let furnished");
  }

  @Test
  void slotValuesAreHtmlEscaped() {
    Map<String, Object> hostile = fullData();
    hostile.put("ownerName", "<script>alert('x')</script>");
    String html = service.previewHtml(request(hostile));

    assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>alert");
  }

  @Test
  void missingFieldsRenderPlaceholdersInPreviewTier() {
    // Only the owner is supplied; the rest fall back to labelled placeholders (preview is
    // tolerant).
    String html = service.previewHtml(request(Map.of("ownerName", "Asha Rao")));

    assertThat(html).contains("Asha Rao");
    assertThat(html).contains("[ Tenant name ]"); // labelled placeholder, never a bare null
    assertThat(html).doesNotContain("null");
  }

  @Test
  void livePaneHtmlIsByteForByteThePdfsHtmlSource() {
    // The single-renderer guarantee: the HTML returned for the live pane equals the HTML handed to
    // the PDF renderer for the same effective template + data.
    DocumentProjectionRequest req = request(fullData());

    service.previewPdf(req);
    String pdfSourceHtml = renderer.lastHtml;
    String paneHtml = service.previewHtml(req);

    assertThat(paneHtml).isEqualTo(pdfSourceHtml);
  }

  @Test
  void livePaneHtmlEqualsThePdfsHtmlSourceForBothInAndTg() {
    // Parity across the new layout for both jurisdictions: one compiler, one compile path, one
    // resolved date -> the live-pane HTML is byte-for-byte the HTML the PDF is rendered from.
    for (DocumentDimensions dims :
        List.of(
            new DocumentDimensions("IN", "residential"),
            new DocumentDimensions("TG", "residential"))) {
      DocumentProjectionRequest req = new DocumentProjectionRequest(dims, fullData());

      service.previewPdf(req);
      String pdfSourceHtml = renderer.lastHtml;
      String paneHtml = service.previewHtml(req);

      assertThat(paneHtml).isEqualTo(pdfSourceHtml);
    }
  }

  @Test
  void blankAgreementDateFallsBackToTheInjectedClocksDate() {
    // No agreementDate submitted -> the execution date resolves to the fixed clock's date
    // (SYSDATE),
    // bound under the reserved key and surfaced in the "Agreement date" row of the Term section.
    String html = productionService.previewHtml(inRequest(fullData()));

    assertThat(html).contains("13-Jul-2026"); // the fixed clock's date, formatted deterministically
  }

  @Test
  void submittedAgreementDateIsUsedOverTheClock() {
    // A submitted agreementDate drives the execution date (not the clock), formatted for display.
    Map<String, Object> withDate = fullData();
    withDate.put("agreementDate", "2026-01-15");
    String html = productionService.previewHtml(inRequest(withDate));

    assertThat(html).contains("15-Jan-2026");
    assertThat(html).doesNotContain("13-Jul-2026"); // the clock fallback is not used
  }

  @Test
  void previewAndPdfShareTheSameResolvedExecutionDate() {
    // Parity extends to the resolved date: a blank agreementDate resolves once and both tiers show
    // the same fixed-clock date, so the previewed document matches the signed document's.
    DocumentProjectionRequest req = inRequest(fullData());

    productionService.previewPdf(req);
    String pdfSourceHtml = renderer.lastHtml;
    String paneHtml = productionService.previewHtml(req);

    assertThat(paneHtml).isEqualTo(pdfSourceHtml);
    assertThat(paneHtml).contains("13-Jul-2026");
  }

  @Test
  void generateProducesPdfBytesAndTheEffectiveTemplateIdentity() {
    // Generate tier over a complete data map: registrationResponsibility (required by the type
    // layer)
    // is filled from its declared default, furnished from its base default, so full validation
    // passes.
    var result = service.generate(request(fullData()));

    assertThat(new String(result.pdf(), StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    assertThat(result.identity().templateId()).isEqualTo("rental-base");
    assertThat(result.identity().contentHash()).isNotBlank();
    // Provenance is keyed by contributing layer id -> version (base + the residential type layer).
    assertThat(result.identity().layerVersions())
        .containsEntry("base", 1)
        .containsEntry("type:residential", 1);
    // Parity holds on the generate path too: the rendered HTML is the same compiler output.
    assertThat(renderer.lastHtml).contains("Asha Rao");
  }

  // --- 4.3 face wiring: PREVIEW from request, GENERATE deferred -------------

  @Test
  void previewFaceDrivesTheCompilerWithTheRequestsActiveSections() {
    // PREVIEW takes its active set from the request: the added optional section appears; without
    // it, the same section is absent.
    String withPets = optionalService.previewHtml(tgOptionalRequest(List.of("Pets")));
    String withoutPets = optionalService.previewHtml(tgOptionalRequest(List.of()));

    assertThat(withPets).contains("<h2>Pets</h2>");
    assertThat(withoutPets).doesNotContain("<h2>Pets</h2>");
  }

  @Test
  void generateFacePassesEmptyActiveSetWhenTheRequestCarriesNone() {
    // The deferred-generate behaviour: AgreementDocumentService builds the request via the two-arg
    // constructor (no activeSections), so generate renders mandatory sections only -- the optional
    // Pets section is absent from the signed draft even though a preview could add it.
    optionalService.generate(
        new DocumentProjectionRequest(
            new DocumentDimensions("TG", "residential"), optionalSetData()));

    assertThat(renderer.lastHtml).doesNotContain("<h2>Pets</h2>");
  }

  @Test
  void generateFacePassesTheActiveSetItIsExplicitlyGiven() {
    // Generate is not hard-wired to empty: when a request explicitly carries an active set,
    // generate
    // honours it (the seam the deferred follow-on will drive from the persisted agreement).
    optionalService.generate(tgOptionalRequest(List.of("Pets")));

    assertThat(renderer.lastHtml).contains("<h2>Pets</h2>");
  }
}

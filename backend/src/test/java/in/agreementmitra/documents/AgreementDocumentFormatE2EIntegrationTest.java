package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.GotenbergTestConfig;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.PageFurniture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * END-TO-END INTEGRATION for the {@code agreement-document-format} umbrella (modules M0-M5), the
 * single top-of-pyramid test proving the six CRs compose over the real HTTP surface for the seeded
 * production Telangana template. Runs under {@code test,sandbox} so the catalog seeder + the
 * registry-backed {@code LayerSource} resolve the real {@code documents/template/sets/rental/} set
 * (not the test fixtures), against real Postgres + MinIO + a real Gotenberg container. Skips (not
 * fails) without Docker.
 *
 * <p>Asserts the composed behaviour end to end:
 *
 * <ul>
 *   <li><b>M0 + M3 + M5</b> -- {@code GET /api/templates/form} marks the mandatory sections
 *       (Owner/Tenant/Schedule/Term/Financial) {@code optional:false} and the add-ons {@code
 *       optional:true}, carries each section's {@code renderKind}, and OMITS the field-less "Now
 *       This Agreement Witnesseth" document-only section from the capture form.
 *   <li><b>M0 + M1 + M5</b> -- {@code POST /api/templates/document/preview} renders the artifact
 *       layout: the {@code meta.document} header (title/subtitle/execution line), Owner/Tenant
 *       party cards, the witnesseth clause list, and real page margins.
 *   <li><b>M2 + M5</b> -- an optional section contributes nothing until its title is in {@code
 *       activeSections}; adding it makes exactly that section appear.
 *   <li><b>M1</b> -- the execution date uses a submitted {@code agreementDate} verbatim, else the
 *       SYSDATE fallback fills the slot.
 *   <li><b>M1 parity</b> -- the PDF face renders from the same compiled document.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({HarnessTestConfig.class, GotenbergTestConfig.class})
@ActiveProfiles({"test", "sandbox"})
@Testcontainers(disabledWithoutDocker = true)
class AgreementDocumentFormatE2EIntegrationTest {

  private static final String FORM = "/api/templates/form";
  private static final String PREVIEW = "/api/templates/document/preview";

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper mapper = new ObjectMapper();

  /** A realistic Telangana working set (the aggregate-backed keys plus a few add-on fields). */
  private static Map<String, Object> telanganaData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("tenantName", "Bhaskar Rao");
    data.put("propertyAddress", "F1, Sri Sai Krishna Apartments, Kukatpally, Hyderabad");
    data.put("monthlyRent", "25000.00");
    data.put("securityDeposit", "100000.00");
    data.put("startDate", "2026-08-01");
    data.put("endDate", "2027-06-30");
    data.put("durationMonths", 11);
    return data;
  }

  private String previewBody(Map<String, Object> data, List<String> activeSections)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dimensions", Map.of("state", "TG", "type", "residential"));
    body.put("data", data);
    body.put("activeSections", activeSections);
    return mapper.writeValueAsString(body);
  }

  private String previewHtml(Map<String, Object> data, List<String> activeSections)
      throws Exception {
    return mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(previewBody(data, activeSections)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // --- M0 + M3 + M5: the capture form carries section semantics and drops document-only sections
  // --

  @Test
  void formSchemaMarksMandatoryOptionalRenderKindAndOmitsTheWitnessSection() throws Exception {
    String json =
        mockMvc
            .perform(get(FORM).param("state", "TG").param("type", "residential"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode sections = mapper.readTree(json).get("sections");

    // The field-bearing sections are present; the field-less "Now This Agreement Witnesseth"
    // document-only section is omitted from the capture form (M3), even though M1 still renders it.
    assertThat(titles(sections))
        .contains(
            "Owner",
            "Tenant",
            "Schedule of Property",
            "Term",
            "Financial",
            "Charges & Utilities",
            "Occupancy & Use",
            "Dispute Resolution")
        .doesNotContain("Now This Agreement Witnesseth");

    // The optional Telangana statutory overlay and the optional Witnesses add-on are surfaced as
    // addable catalog entries. The "In Witness Whereof" signature block is now MANDATORY and, being
    // a
    // SIGNATURES (document-only) section, is omitted from the capture form -- like the field-less
    // MANDATORY witnesseth section.
    assertThat(titles(sections)).contains("Statutory (Telangana)", "Witnesses");
    assertThat(titles(sections)).doesNotContain("In Witness Whereof");

    // Mandatory vs optional flags (M0 declaration surfaced by M3).
    assertThat(section(sections, "Owner").get("optional").asBoolean()).isFalse();
    assertThat(section(sections, "Tenant").get("optional").asBoolean()).isFalse();
    assertThat(section(sections, "Term").get("optional").asBoolean()).isFalse();
    assertThat(section(sections, "Financial").get("optional").asBoolean()).isFalse();
    assertThat(section(sections, "Charges & Utilities").get("optional").asBoolean()).isTrue();
    assertThat(section(sections, "Occupancy & Use").get("optional").asBoolean()).isTrue();
    // MANDATORY since 2026-09-07: the TG residential layer drops the national
    // stampRegistrationClause from the witnesseth list, so an opt-in statutory section left a
    // Telangana deed with no stamp/registration clause at all. See state-TG.patch.yaml.
    assertThat(section(sections, "Statutory (Telangana)").get("optional").asBoolean()).isFalse();
    assertThat(section(sections, "Witnesses").get("optional").asBoolean()).isTrue();

    // Render kinds (M0 declaration surfaced by M3, lowercase opaque token).
    assertThat(section(sections, "Owner").get("renderKind").asText()).isEqualTo("parties");
    assertThat(section(sections, "Tenant").get("renderKind").asText()).isEqualTo("parties");
    assertThat(section(sections, "Financial").get("renderKind").asText()).isEqualTo("keyvalue");
    assertThat(section(sections, "Witnesses").get("renderKind").asText()).isEqualTo("keyvalue");
  }

  // --- M0 + M1 + M5: the preview renders the artifact layout from meta.document + render kinds
  // -----

  @Test
  void previewRendersTheArtifactLayoutHeaderPartyCardsWitnessSectionAndMargins() throws Exception {
    String html = previewHtml(telanganaData(), List.of());

    // meta.document header (title/subtitle/execution line). Header escaping is covered as a unit
    // in TemplateCompilerTest.headerTextContainingMarkupRendersAsLiteralText -- the production
    // subtitle no longer carries an ampersand since base.yaml v2 dropped "(Leave & Licence)".
    assertThat(html).contains("<h1 class=\"doc-title\">Rental Agreement</h1>");
    assertThat(html).contains("Residential Tenancy");
    assertThat(html).doesNotContain("Licence");
    assertThat(html).contains("This Agreement is executed on");

    // Owner + Tenant render as party cards (render: parties).
    assertThat(html).contains("<h2>Owner</h2>").contains("<h2>Tenant</h2>");
    assertThat(html).contains("<div class=\"party-card\">");

    // The document-only witnesseth clause list renders (render: clauses). The TG statutory overlay
    // is opt-in (absent from this default preview); the mandatory signature block IS present
    // (asserted in statutoryIsOptInButSignatureBlockIsMandatoryForTelangana).
    assertThat(html).contains("<h2>Now This Agreement Witnesseth</h2>");
    assertThat(html).contains("<ol class=\"clauses\">");

    // Real page margins on both tiers (M1): CSS @page for the PDF, body padding for the live pane.
    assertThat(html).contains("@page { margin:");
  }

  // --- Statutory (Telangana) and the execution / signature block are BOTH mandatory --------------

  @Test
  void statutoryAndSignatureBlockAreBothMandatoryForTelangana() throws Exception {
    // With NO add-ons selected, a Telangana deed must still carry the statutory overlay. This
    // reverses the 2026-07-13 opt-in decision (see state-TG.patch.yaml): the TG residential layer
    // re-authors the witnesseth list WITHOUT the national stampRegistrationClause, so while the
    // statutory section was opt-in a default TG deed carried NO stamp/registration clause at all --
    // strictly worse than the national template, which always carries one.
    String without = previewHtml(telanganaData(), List.of());
    assertThat(without)
        .contains("<h2>Statutory (Telangana)</h2>")
        // The clause the whole flag exists for.
        .contains("compulsorily registered before the jurisdictional Sub-Registrar")
        // The TG-specific statute, not merely "laws of India".
        .contains("Telangana Buildings (Lease, Rent and Eviction) Control Act, 1960")
        // The tenant protection: no cutting water/electricity during the tenancy.
        .contains("withhold or disconnect essential supplies")
        // The signature block stays mandatory too -- the draft must be signable.
        .contains("<h2>In Witness Whereof</h2>")
        .contains("esign:owner")
        .contains("esign:tenant");

    // The stamp-amount clause stays gated on an entered amount, so a deed with no amount captured
    // renders the overlay without an empty "stamp duty paid is INR" sentence.
    assertThat(without).doesNotContain("The stamp duty paid on this Agreement is INR");
  }

  // --- M2 + M5: optional sections are opt-in -- absent until added to activeSections
  // --------------

  @Test
  void optionalSectionAppearsOnlyOnceAddedToActiveSections() throws Exception {
    // Not added -> the optional "Occupancy & Use" section contributes nothing (title escaped in the
    // heading). Mandatory sections still render.
    String without = previewHtml(telanganaData(), List.of());
    assertThat(without).doesNotContain("<h2>Occupancy &amp; Use</h2>");
    assertThat(without).contains("<h2>Owner</h2>"); // mandatory still there

    // Added -> exactly that section now appears (the compiler matches the raw title in the active
    // set; the heading is HTML-escaped).
    String withOccupancy = previewHtml(telanganaData(), List.of("Occupancy & Use"));
    assertThat(withOccupancy).contains("<h2>Occupancy &amp; Use</h2>");

    // An unknown/typo active title is ignored (no oracle, no error): the response is still a normal
    // document with no extra section.
    String unknown = previewHtml(telanganaData(), List.of("Not A Section"));
    assertThat(unknown).contains("<h2>Owner</h2>").doesNotContain("<h2>Occupancy &amp; Use</h2>");
  }

  // --- M1: execution date uses submitted agreementDate, else the SYSDATE fallback fills the slot
  // ---

  @Test
  void executionDateUsesSubmittedAgreementDateElseFallsBackToSysdate() throws Exception {
    // Submitted agreementDate drives the execution line, formatted for display as dd-MMM-yyyy.
    Map<String, Object> withDate = telanganaData();
    withDate.put("agreementDate", "2026-08-01");
    assertThat(previewHtml(withDate, List.of()))
        .contains("This Agreement is executed on 01-Aug-2026 in respect of the property");

    // No agreementDate -> the SYSDATE fallback fills the slot: the placeholder is gone and a real
    // date is present (never the raw {{agreementDate}} token).
    String fallback = previewHtml(telanganaData(), List.of());
    assertThat(fallback).doesNotContain("{{agreementDate}}").doesNotContain("[ Agreement date ]");
  }

  // --- M1 parity: the PDF face renders from the same compiled document
  // ----------------------------

  @Test
  void pdfFaceRendersFromTheSameCompiledDocument() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    .content(previewBody(telanganaData(), List.of("Occupancy & Use"))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andReturn();

    byte[] pdf = result.getResponse().getContentAsByteArray();
    assertThat(pdf.length).isGreaterThan(1000);
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
  }

  // --- signature zone: invisible anchors that still reach the PDF text layer
  // -------------------

  /**
   * The anchor is painted in the page colour so no reader sees a machine token on a legal
   * instrument -- but signature placement locates it by reading the PDF's TEXT LAYER. Hiding it
   * with {@code display:none} or {@code visibility:hidden} would render nothing, drop the glyphs,
   * and every signing request would then be refused with "anchor missing".
   *
   * <p>Only a real Chromium render can tell those apart, which is why this lives here and not in a
   * compiler unit test: the HTML looks identical either way.
   */
  @Test
  void invisibleSignatureAnchorsStillReachThePdfTextLayer() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    .content(previewBody(telanganaData(), List.of())))
            .andExpect(status().isOk())
            .andReturn();

    String text;
    try (org.apache.pdfbox.pdmodel.PDDocument document =
        org.apache.pdfbox.Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
      text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
    }

    assertThat(text).contains("esign:owner").contains("esign:tenant");
    // ...and the wet-ink furniture is gone from the rendered instrument: an eSigned document takes
    // its date from the eSign appearance, and nothing here verifies a name against Aadhaar.
    assertThat(text).doesNotContain("as per Aadhaar");
    assertThat(text).doesNotContain("Place: ____");
  }

  // --- helpers
  // ------------------------------------------------------------------------------------

  private static List<String> titles(JsonNode sections) {
    return java.util.stream.StreamSupport.stream(sections.spliterator(), false)
        .map(s -> s.get("title").asText())
        .toList();
  }

  private static JsonNode section(JsonNode sections, String title) {
    for (JsonNode s : sections) {
      if (s.get("title").asText().equals(title)) {
        return s;
      }
    }
    throw new AssertionError("section not found in FormSchema: " + title);
  }

  // --- page furniture: the band the signature strip lives in must stay empty
  // --------------------

  /**
   * Nothing the renderer prints may enter the band reserved for the per-page eSign signature strip.
   *
   * <p><b>This is the test that was missing.</b> The strip is placed by the {@code signing} module,
   * which cannot see this module's margins; this module cannot see the strip. For a while neither
   * side checked the overlap, and the result was a signature drawn across the last lines of text on
   * every page of a legal instrument - accepted by the provider without error, visible only by
   * looking at a rendered page.
   *
   * <p>So the contract is asserted from both ends against {@link PageFurniture}: here, that the
   * band is empty; and in the placement adapter's own test, that the strip lands inside it. Only a
   * real Chromium render can prove this half - the compiled HTML says nothing about where Chromium
   * will break a page.
   */
  @Test
  void bodyTextNeverEntersTheReservedSignatureStripBand() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    // Enough content to run past one page, so the assertion covers a page whose
                    // text reaches the bottom rather than a short one that never gets near it.
                    .content(
                        previewBody(
                            telanganaData(), List.of("Occupancy & Use", "Annexure", "Witnesses"))))
            .andExpect(status().isOk())
            .andReturn();

    byte[] pdf = result.getResponse().getContentAsByteArray();

    // A single-page render would pass this trivially without ever exercising a page whose text runs
    // to the bottom -- which is exactly the case that was broken.
    assertThat(pageCount(pdf)).as("pages rendered").isGreaterThan(1);
    assertThat(glyphsInStripBand(pdf))
        .as("glyphs found inside the reserved signature-strip band (page:yFromBottom)")
        .isEmpty();
  }

  private static int pageCount(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }

  /**
   * Every glyph sitting in the strip band, as {@code page:y} labels. Deliberately returns the
   * offenders rather than a boolean: a failure that names the page and height is one somebody can
   * act on, and the glyph text itself is never included (the instrument carries party PII).
   */
  private static List<String> glyphsInStripBand(byte[] pdf) throws IOException {
    List<String> intrusions = new ArrayList<>();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDFTextStripper stripper =
          new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
              PDPage page = getCurrentPage();
              float pageHeight = page.getCropBox().getHeight();
              for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isBlank()) {
                  continue;
                }
                // PDFBox reports y from the TOP; the band is measured from the bottom.
                float yFromBottom = pageHeight - position.getYDirAdj();
                if (yFromBottom >= PageFurniture.FOOTER_BAND_TOP_PT
                    && yFromBottom < PageFurniture.CONTENT_FLOOR_PT) {
                  intrusions.add(getCurrentPageNo() + ":" + Math.round(yFromBottom));
                }
              }
            }
          };
      stripper.setSortByPosition(true);
      stripper.setStartPage(1);
      stripper.setEndPage(document.getNumberOfPages());
      stripper.getText(document);
    }
    return intrusions;
  }
}

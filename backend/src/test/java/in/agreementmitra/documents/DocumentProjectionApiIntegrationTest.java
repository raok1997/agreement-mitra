package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.HarnessTestConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration test for document projection over HTTP against real Postgres + MinIO and a
 * real Gotenberg container (Testcontainers). Covers the stateless {@code POST
 * /api/templates/document/preview} contract (content negotiation, {@code no-store}, CSP,
 * placeholders, RFC 9457 on out-of-bounds, nothing persisted), the Gotenberg PDF happy-path over
 * the definition-compiled HTML, the outbound-deny render guard, and log hygiene across both the
 * stateless preview and the id-bound {@code GET /api/agreements/{id}/preview}. Skips (not fails)
 * without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DocumentProjectionApiIntegrationTest {

  private static final String PREVIEW = "/api/templates/document/preview";

  @Container
  static final GenericContainer<?> gotenberg =
      new GenericContainer<>(
              new ImageFromDockerfile().withFileFromPath(".", Path.of("..", "docker", "gotenberg")))
          .withExposedPorts(3000)
          // Deny Chromium's outbound network -- the SSRF/exfil guard (matches docker-compose).
          .withEnv("CHROMIUM_DENY_PUBLIC_IPS", "true")
          .withEnv("CHROMIUM_DENY_PRIVATE_IPS", "true");

  @DynamicPropertySource
  static void gotenbergUrl(DynamicPropertyRegistry registry) {
    registry.add(
        "gotenberg.url",
        () -> "http://" + gotenberg.getHost() + ":" + gotenberg.getMappedPort(3000));
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper mapper = new ObjectMapper();

  private long agreementRowCount() {
    Long count = jdbc.queryForObject("SELECT count(*) FROM agreement", Long.class);
    return count == null ? -1 : count;
  }

  private static Map<String, Object> fullData(String ownerName) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", ownerName);
    data.put("tenantName", "Bhaskar Rao");
    data.put("monthlyRent", "25000.00");
    data.put("durationMonths", 11);
    data.put("furnished", Boolean.TRUE);
    return data;
  }

  private String previewBody(Map<String, Object> data) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", data);
    return mapper.writeValueAsString(body);
  }

  // --- 4.4 stateless preview contract --------------------------------------

  @Test
  void htmlVariantReturnsCompiledHtmlNoStoreWithCspAndPersistsNothing() throws Exception {
    long before = agreementRowCount();

    mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(previewBody(fullData("Asha Rao"))))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
        .andExpect(content().string(containsString("<h2>Parties</h2>")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "This Agreement is made between Asha Rao (Owner) and Bhaskar Rao"
                            + " (Tenant).")));

    assertThat(agreementRowCount()).isEqualTo(before); // nothing persisted
  }

  @Test
  void pdfVariantReturnsInlinePdfNoStoreAndPersistsNothing() throws Exception {
    long before = agreementRowCount();

    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    .content(previewBody(fullData("Asha Rao"))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
            .andReturn();

    byte[] pdf = result.getResponse().getContentAsByteArray();
    assertThat(pdf.length).isGreaterThan(1000);
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    assertThat(agreementRowCount()).isEqualTo(before); // nothing persisted
  }

  @Test
  void missingFieldsRenderPlaceholders() throws Exception {
    // Only the owner is supplied -- preview is tolerant, so the rest become labelled placeholders.
    mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(previewBody(Map.of("ownerName", "Asha Rao"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("[ Tenant name ]")));
  }

  @Test
  void outOfBoundsValueIsRejectedRfc9457WithoutEchoingTheValue() throws Exception {
    // durationMonths 999 exceeds the declared max (60) -> rejected before any render, no value
    // echoed.
    Map<String, Object> data = fullData("Asha Rao");
    data.put("durationMonths", 999);

    mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(previewBody(data)))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("document-data-invalid")))
        .andExpect(content().string(not(containsString("999"))));
  }

  // --- 4.3 Gotenberg render leg --------------------------------------------

  @Test
  void remoteReferenceInDataTriggersNoOutboundRequestAndStillRenders() throws Exception {
    // A data value carrying a URL: it is escaped to text at compile time (never a fetched
    // resource),
    // and Gotenberg's outbound-deny blocks any request regardless -- the PDF still renders.
    Map<String, Object> data = fullData("http://169.254.169.254/latest/meta-data/");

    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    .content(previewBody(data)))
            .andExpect(status().isOk())
            .andReturn();

    byte[] pdf = result.getResponse().getContentAsByteArray();
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
  }

  // --- provenance line (body, preview + PDF) + page-number furniture (PDF) -----

  @Test
  void statelessPreviewHtmlBodyCarriesTheProvenanceLineMarkerAndUrl() throws Exception {
    // The point of moving the reference + URL into the body: the reader sees them in the ON-SCREEN
    // preview (HTML), not only in the PDF. Pre-save the provenance line shows the marker + the URL.
    mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(previewBody(fullData("Asha Rao"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("PREVIEW - NOT FOR EXECUTION")))
        .andExpect(content().string(containsString("agreementmitra.com")));
  }

  @Test
  void aSuppliedReferenceReplacesTheMarkerInThePreviewHtmlBody() throws Exception {
    // Post-save the client passes the real tracking number as documentReference; the preview body
    // shows it instead of the marker (still HTML-escaped, still in parity with the PDF body).
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", fullData("Asha Rao"));
    body.put("documentReference", "AM-A5E4D7-010726");

    mockMvc
        .perform(
            post(PREVIEW)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AM-A5E4D7-010726")))
        .andExpect(content().string(not(containsString("PREVIEW - NOT FOR EXECUTION"))));
  }

  @Test
  void statelessPreviewPdfCarriesThePreviewMarkerPlatformUrlAndPageNumbers() throws Exception {
    // The capture-screen Download PDF (stateless, no saved agreement) carries the provenance line
    // (marker + platform URL, from the body) and page numbers (furniture) -- all extractable text.
    MvcResult result =
        mockMvc
            .perform(
                post(PREVIEW)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF)
                    .content(previewBody(fullData("Asha Rao"))))
            .andExpect(status().isOk())
            .andReturn();

    String text = flattenWhitespace(pdfText(result.getResponse().getContentAsByteArray()));
    assertThat(text)
        .contains("PREVIEW - NOT FOR EXECUTION")
        .contains("agreementmitra.com")
        // "Agreement page X of Y", not a bare "Page X of Y": the count describes the agreement,
        // which the e-stamp certificate is later bound in front of as page 1. See
        // GotenbergClient#footerHtml.
        .contains("Agreement page 1 of");
  }

  @Test
  void idBoundPreviewPdfCarriesThePersistedTrackingReferencePlatformUrlAndPageNumbers()
      throws Exception {
    // The saved-agreement render stamps the agreement's ONE persisted tracking reference -- the
    // same value the customer is given and staff quote at stamp intake -- plus the platform URL
    // and page numbers, and not the preview marker.
    UUID id = createAgreement("Asha");

    MvcResult result =
        mockMvc
            .perform(get("/api/agreements/{id}/preview", id))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andReturn();

    byte[] pdf = result.getResponse().getContentAsByteArray();
    int pages = pageCount(pdf);
    String text = flattenWhitespace(pdfText(pdf));
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, id);

    // The footer is furniture in the margin, stamped once PER PAGE -- so the reference appears
    // exactly
    // `pages` times. The on-screen provenance line is screen-only (hidden in print), so it adds NO
    // extra occurrence (a body-flow line would add one and, on a full page, orphan onto its own
    // page).
    assertThat(countOccurrences(text, reference)).isEqualTo(pages);
    assertThat(text)
        .contains(reference)
        .contains("agreementmitra.com")
        .contains("Agreement page 1 of " + pages) // the "of Y" total renders
        .doesNotContain("PREVIEW - NOT FOR EXECUTION");
  }

  /** Extract all text from a PDF (page body + Chromium footer furniture) with PDFBox. */
  private static String pdfText(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /** The number of pages in a PDF. */
  private static int pageCount(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return doc.getNumberOfPages();
    }
  }

  /** Count non-overlapping occurrences of {@code needle} in {@code haystack}. */
  private static long countOccurrences(String haystack, String needle) {
    long count = 0;
    for (int i = haystack.indexOf(needle);
        i >= 0;
        i = haystack.indexOf(needle, i + needle.length())) {
      count++;
    }
    return count;
  }

  /**
   * Collapse runs of whitespace to single spaces so footer-cell assertions are wrap-insensitive.
   */
  private static String flattenWhitespace(String text) {
    return text.replaceAll("\\s+", " ");
  }

  // --- 4.5 log hygiene ------------------------------------------------------

  @Test
  void statelessPreviewLeavesNoPiiOrBytesInLogs() throws Exception {
    String distinctive = "Zephyrina";
    String logged =
        captureRootLogsWhile(
            () ->
                mockMvc
                    .perform(
                        post(PREVIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_PDF)
                            .content(previewBody(fullData(distinctive))))
                    .andExpect(status().isOk()));

    assertThat(logged).doesNotContain(distinctive); // party PII never logged
    assertThat(logged).doesNotContain("%PDF-"); // rendered bytes never logged
  }

  @Test
  void idBoundPreviewLeavesNoPiiOrBytesInLogs() throws Exception {
    String distinctive = "Zephyrina";
    UUID id = createAgreement(distinctive);

    String logged =
        captureRootLogsWhile(
            () ->
                mockMvc
                    .perform(get("/api/agreements/{id}/preview", id))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)));

    assertThat(logged).doesNotContain(distinctive); // composed party details never logged
    assertThat(logged).doesNotContain("Veeranjaneyulu"); // father's name never logged
    assertThat(logged).doesNotContain("%PDF-"); // rendered bytes never logged
  }

  /** Create a persisted agreement whose owner name embeds {@code distinctiveOwnerFirstName}. */
  private UUID createAgreement(String distinctiveOwnerFirstName) throws Exception {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "F1, Sri Sai Krishna Apartments, Kukatpally, Hyderabad",
            "monthlyRent", "16000.00",
            "securityDeposit", "32000.00",
            "startDate", "2026-07-01",
            "endDate", "2027-06-01",
            "signers",
                List.of(
                    Map.of(
                        "firstName", "Venkata",
                        "lastName", "Padavala",
                        "fatherName", "Veeranjaneyulu",
                        "currentAddress", "HIG 133",
                        "email", "tenant@example.com",
                        "role", "TENANT"),
                    Map.of(
                        "firstName", distinctiveOwnerFirstName,
                        "lastName", "Kowthavarapu",
                        "fatherName", "Sivarama Krishna",
                        "currentAddress", "Sri Sai Krishna Apartments",
                        "email", "owner@example.com",
                        "role", "OWNER")));
    MvcResult created =
        mockMvc
            .perform(
                post("/api/agreements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        mapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
  }

  /** Run {@code action} with a root-logger appender attached, returning the concatenated log. */
  private static String captureRootLogsWhile(ThrowingRunnable action) throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      action.run();
      return appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .reduce("", (a, b) -> a + "\n" + b);
    } finally {
      root.detachAppender(appender);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}

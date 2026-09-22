package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.GotenbergTestConfig;
import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for the id-bound agreement preview against real Postgres + MinIO
 * (Testcontainers) and a real Gotenberg container built from {@code docker/gotenberg}: the HTTP
 * surface, the security permit, the agreement-to-declared-field-keys mapping, the definition-driven
 * compiler render (sourced from the same compiler that produces the signed PDF), the
 * inline/no-store headers, that nothing is persisted, and log hygiene. Skips (not fails) without
 * Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, GotenbergTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementPreviewIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper mapper = new ObjectMapper();

  private UUID createAgreement(String tenantName) {
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
                        "firstName", tenantName,
                        "lastName", "Padavala",
                        "fatherName", "Veeranjaneyulu",
                        "currentAddress", "HIG 133",
                        "email", "tenant@example.com",
                        "role", "TENANT"),
                    Map.of(
                        "firstName", "Bindu",
                        "lastName", "Kowthavarapu",
                        "fatherName", "Sivarama Krishna",
                        "currentAddress", "Sri Sai Krishna Apartments",
                        "email", "owner@example.com",
                        "role", "OWNER")));
    ResponseEntity<String> created = rest.postForEntity("/api/agreements", body, String.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    try {
      return UUID.fromString(mapper.readTree(created.getBody()).path("id").asText());
    } catch (Exception e) {
      throw new AssertionError("could not parse created agreement id", e);
    }
  }

  @Test
  void previewReturnsInlinePdfNoStoreAndPersistsNothing() {
    UUID id = createAgreement("Venkata");

    ResponseEntity<byte[]> resp =
        rest.getForEntity("/api/agreements/{id}/preview", byte[].class, id);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getHeaders().getContentType())
        .isNotNull()
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PDF));
    assertThat(resp.getHeaders().getFirst("Content-Disposition")).startsWith("inline");
    assertThat(resp.getHeaders().getCacheControl()).contains("no-store");
    byte[] pdf = resp.getBody();
    assertThat(pdf).isNotNull();
    assertThat(pdf.length).isGreaterThan(1000);
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

    // A preview stores nothing -- no draft key is set.
    String draftKey =
        jdbc.queryForObject("SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, id);
    assertThat(draftKey).isNull();
  }

  @Test
  void previewUnknownIdIs404AndNonUuidIs400() {
    ResponseEntity<String> notFound =
        rest.getForEntity("/api/agreements/{id}/preview", String.class, UUID.randomUUID());
    assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<String> badRequest =
        rest.getForEntity("/api/agreements/not-a-uuid/preview", String.class);
    assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void previewComposesDefinitionDrivenSectionsFromTheCompiler() throws Exception {
    UUID id = createAgreement("Venkata");

    ResponseEntity<byte[]> resp =
        rest.getForEntity("/api/agreements/{id}/preview", byte[].class, id);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

    String text;
    try (PDDocument doc = Loader.loadPDF(resp.getBody())) {
      text = new PDFTextStripper().getText(doc);
    }
    // Rendered line-wrapping depends on the page layout (margins + font), so collapse runs of
    // whitespace to a single space before asserting on clause text -- the content must be present,
    // the wrap points are a layout concern this assertion should not couple to.
    String flat = text.replaceAll("\\s+", " ");
    // The reference definition declares owner/tenant name, rent, and duration; the agreement is
    // mapped to exactly those declared field keys (design D-C), so the compiled document reflects
    // the definition's fields and clauses -- not the retired hardcoded template. Fields the
    // definition does not declare (father's name, property, deposit, dates) are out of scope here.
    assertThat(flat)
        .contains("Venkata Padavala") // tenant name (declared field: tenantName)
        .contains("Bindu Kowthavarapu") // owner name (declared field: ownerName)
        .contains("16000.00") // monthly rent
        .contains("This Agreement is made between Bindu Kowthavarapu (Owner) and Venkata Padavala")
        .contains("The premises shall be used for residential purposes only.");
    assertThat(flat.toLowerCase())
        .contains("parties")
        .contains("financial")
        .contains("residential");
    // Fields the reference definition does not declare do not render.
    assertThat(flat).doesNotContain("Veeranjaneyulu").doesNotContain("Kukatpally");
  }

  @Test
  void previewLeavesNoPiiInLogs() {
    String distinctiveTenant = "Zephyrina";
    UUID id = createAgreement(distinctiveTenant);

    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      ResponseEntity<byte[]> resp =
          rest.getForEntity("/api/agreements/{id}/preview", byte[].class, id);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

      String logged =
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(logged).doesNotContain(distinctiveTenant); // party PII never logged
      assertThat(logged).doesNotContain("%PDF-"); // rendered bytes never logged
      assertThat(logged).doesNotContain("Veeranjaneyulu"); // father's name never logged
    } finally {
      root.detachAppender(appender);
    }
  }
}

package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.TemplateFormApi;
import in.agreementmitra.support.GotenbergTestConfig;
import in.agreementmitra.support.HarnessTestConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The money test (agreement-template-selection) against real Postgres + MinIO (Testcontainers) and
 * a real Gotenberg container: create an agreement with {@code (TG, residential)} ->
 * generate-as-draft -> the stored draft renders the SELECTED TG effective template, not the default
 * IN one.
 *
 * <p>Proof: the pinned {@code template_content_hash} equals the TG effective-template content hash
 * (obtained from the public form-projection port, which carries {@code effective.contentHash()})
 * and differs from the IN hash. Parity: the stateless preview for the same {@code (TG, data)}
 * renders the TG overlay (the stamp-duty clause + the "in advance" rent variant) that the draft was
 * rendered from, while the IN preview does not -- so what a user previews is what gets signed.
 * Also: creating with a {@code (state, type)} no published template covers is cleanly rejected
 * (404, no echo).
 *
 * <p>The seeder does NOT run under the {@code test} profile, so this test seeds the catalog rows it
 * needs itself. Skips (not fails) without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, GotenbergTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementTemplateSelectionGenerateIntegrationTest {

  private static final String REF = "documents/template/examples/layers/";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TemplateFormApi templateForm;
  @Autowired private DocumentProjectionApi documentProjection;

  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void seedCatalog() {
    jdbc.update("DELETE FROM template");
    insertTemplate("Residential Rental (National)", "IN", "residential");
    insertTemplate("Residential Rental (Telangana)", "TG", "residential");
  }

  private void insertTemplate(String name, String state, String type) {
    jdbc.update(
        "INSERT INTO template (id, name, description, type, state, language, version, status,"
            + " layer_set_ref, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        name,
        name + " blurb",
        type,
        state,
        "en",
        1,
        "PUBLISHED",
        REF,
        java.sql.Timestamp.from(Instant.now()));
  }

  private UUID createAgreement(String state, String type) {
    Map<String, Object> body = new HashMap<>();
    body.put("propertyAddress", "F1, Kukatpally, Hyderabad");
    body.put("monthlyRent", "16000.00");
    body.put("securityDeposit", "32000.00");
    body.put("startDate", "2026-07-01");
    body.put("endDate", "2027-06-01");
    body.put(
        "signers",
        java.util.List.of(
            Map.of(
                "firstName", "Venkata",
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
    if (state != null) {
      body.put("state", state);
    }
    if (type != null) {
      body.put("type", type);
    }
    ResponseEntity<String> created = rest.postForEntity("/api/agreements", body, String.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    try {
      return UUID.fromString(mapper.readTree(created.getBody()).path("id").asText());
    } catch (Exception e) {
      throw new AssertionError("could not parse created agreement id", e);
    }
  }

  private String pinnedHash(UUID id) {
    return jdbc.queryForObject(
        "SELECT template_content_hash FROM agreement WHERE id = ?", String.class, id);
  }

  private static Map<String, Object> coreData() {
    // Mirrors AgreementDocumentMapper's declared keys; monthlyRent > 0 fires the TG rent showWhen.
    Map<String, Object> data = new HashMap<>();
    data.put("ownerName", "Bindu Kowthavarapu");
    data.put("tenantName", "Venkata Padavala");
    data.put("monthlyRent", "16000.00");
    data.put("durationMonths", 11);
    return data;
  }

  @Test
  void tgSelectionRendersAndPinsTheTelanganaTemplateNotTheDefault() {
    String tgHash = templateForm.formFor("TG", "residential").contentHash();
    String inHash = templateForm.formFor("IN", "residential").contentHash();
    assertThat(tgHash).isNotEqualTo(inHash); // guard: the two effective templates differ

    UUID id = createAgreement("TG", "residential");
    assertThat(generate(id).getStatusCode()).isEqualTo(HttpStatus.OK);

    // THE money assertion: the draft was rendered from (and pinned to) the TG effective template.
    assertThat(pinnedHash(id)).isEqualTo(tgHash).isNotEqualTo(inHash);

    // Parity: the stateless preview for the same (TG, data) renders the TG overlay the draft used.
    String tgPreview =
        documentProjection.previewHtml(
            new DocumentProjectionRequest(new DocumentDimensions("TG", "residential"), coreData()));
    assertThat(tgPreview).contains("Telangana").contains("in advance");

    // The IN preview (the default) is a DIFFERENT document -- no TG statutory overlay.
    String inPreview =
        documentProjection.previewHtml(
            new DocumentProjectionRequest(new DocumentDimensions("IN", "residential"), coreData()));
    assertThat(inPreview).doesNotContain("Telangana");
  }

  @Test
  void createWithAnUncoveredDimensionPairIsCleanlyRejected() {
    Map<String, Object> body = new HashMap<>();
    body.put("propertyAddress", "F1, Kukatpally, Hyderabad");
    body.put("monthlyRent", "16000.00");
    body.put("securityDeposit", "32000.00");
    body.put("startDate", "2026-07-01");
    body.put("endDate", "2027-06-01");
    body.put(
        "signers",
        java.util.List.of(
            Map.of(
                "firstName",
                "Venkata",
                "lastName",
                "Padavala",
                "fatherName",
                "Veeranjaneyulu",
                "currentAddress",
                "HIG 133",
                "role",
                "TENANT"),
            Map.of(
                "firstName",
                "Bindu",
                "lastName",
                "Kowthavarapu",
                "fatherName",
                "Sivarama Krishna",
                "currentAddress",
                "Apartments",
                "role",
                "OWNER")));
    body.put("state", "ZZ"); // no published template covers (ZZ, residential)
    body.put("type", "residential");

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", body, String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    // No-oracle: the rejected dimensions are never echoed back.
    assertThat(resp.getBody()).doesNotContain("ZZ");
  }

  private ResponseEntity<String> generate(UUID id) {
    return rest.postForEntity("/api/agreements/{id}/document", null, String.class, id);
  }
}

package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.HarnessTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Full-pipeline integration test for the catalog HTTP surface against real Postgres
 * (Testcontainers): the endpoints, the security permits, the published-only query, JSON
 * serialization, the Flyway V8 migration, and {@code ddl-auto: validate}. The seeder does NOT run
 * under the {@code test} profile, so this test controls the catalog rows itself and asserts exact
 * visibility. {@code disabledWithoutDocker = true} makes it skip (not fail) without a Docker
 * daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TemplateCatalogApiIntegrationTest {

  private static final String REF = "documents/template/examples/layers/";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper mapper = new ObjectMapper();

  private UUID alpha; // published TG residential
  private UUID beta; // published KA commercial
  private UUID draft; // draft TG residential (hidden)
  private UUID deprecated; // deprecated MH residential (hidden)

  @BeforeEach
  void seedRows() {
    jdbc.update("DELETE FROM template");
    alpha = insert("Alpha Residential Rental", "TG", "residential", "PUBLISHED");
    beta = insert("Beta Commercial Lease", "KA", "commercial", "PUBLISHED");
    draft = insert("Gamma Draft Rental", "TG", "residential", "DRAFT");
    deprecated = insert("Delta Retired Rental", "MH", "residential", "DEPRECATED");
  }

  private UUID insert(String name, String state, String type, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO template (id, name, description, type, state, language, version, status,"
            + " layer_set_ref, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        name,
        name + " blurb",
        type,
        state,
        "en",
        1,
        status,
        REF,
        java.sql.Timestamp.from(Instant.now()));
    return id;
  }

  private JsonNode getJson(String path) throws Exception {
    ResponseEntity<String> resp = rest.getForEntity(path, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    return mapper.readTree(resp.getBody());
  }

  @Test
  void listReturnsOnlyPublishedEntries() throws Exception {
    JsonNode body = getJson("/api/templates");
    assertThat(body.isArray()).isTrue();
    assertThat(idsOf(body)).containsExactlyInAnyOrder(alpha.toString(), beta.toString());
    // No draft/deprecated ever appears.
    assertThat(idsOf(body)).doesNotContain(draft.toString(), deprecated.toString());
  }

  @Test
  void filterByStateAndTypeNarrows() throws Exception {
    JsonNode body = getJson("/api/templates?state=TG&type=residential");
    assertThat(idsOf(body)).containsExactly(alpha.toString());
  }

  @Test
  void freeTextQueryFiltersByNameOrDescription() throws Exception {
    JsonNode body = getJson("/api/templates?q=commercial");
    assertThat(idsOf(body)).containsExactly(beta.toString());

    // A query matching a draft's text still excludes the draft.
    JsonNode draftQuery = getJson("/api/templates?q=Gamma");
    assertThat(idsOf(draftQuery)).isEmpty();
  }

  @Test
  void detailReturnsPublishedEntryWithDimensions() throws Exception {
    JsonNode body = getJson("/api/templates/" + alpha);
    assertThat(body.path("id").asText()).isEqualTo(alpha.toString());
    assertThat(body.path("dimensions").path("state").asText()).isEqualTo("TG");
    assertThat(body.path("dimensions").path("type").asText()).isEqualTo("residential");
    assertThat(body.path("dimensions").path("language").asText()).isEqualTo("en");
    assertThat(body.path("version").asInt()).isEqualTo(1);
  }

  @Test
  void unknownDraftAndDeprecatedIdsAllReturnIndistinguishable404() {
    UUID unknown = UUID.randomUUID();
    String draftBody = expect404("/api/templates/" + draft);
    String deprecatedBody = expect404("/api/templates/" + deprecated);
    String unknownBody = expect404("/api/templates/" + unknown);

    // Same RFC 9457 shape for all three; no id echoed (no existence oracle).
    assertThat(draftBody).isEqualTo(unknownBody);
    assertThat(deprecatedBody).isEqualTo(unknownBody);
    assertThat(unknownBody).doesNotContain(draft.toString()).doesNotContain(deprecated.toString());
  }

  @Test
  void flywayV8CatalogMigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);

    // The shared template_id column (added here as the catalog-lands-first fallback) exists on
    // agreement and validate passed at boot.
    Integer col =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'agreement' AND"
                + " column_name = 'template_id'",
            Integer.class);
    assertThat(col).isEqualTo(1);
  }

  private String expect404(String path) {
    ResponseEntity<String> resp = rest.getForEntity(path, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getHeaders().getContentType())
        .isNotNull()
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    return resp.getBody();
  }

  private static java.util.List<String> idsOf(JsonNode array) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    array.forEach(n -> ids.add(n.path("id").asText()));
    return ids;
  }
}

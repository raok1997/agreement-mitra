package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for generate-as-draft against real Postgres + MinIO
 * (Testcontainers) and a real Gotenberg container: the endpoint renders the rental-agreement
 * template and stores the PDF as the agreement's draft via the existing draft path, overwriting
 * while unsigned and locking (409) once a signing request exists. Skips (not fails) without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementGenerateDocumentIntegrationTest {

  @Container
  static final GenericContainer<?> gotenberg =
      new GenericContainer<>(
              new ImageFromDockerfile().withFileFromPath(".", Path.of("..", "docker", "gotenberg")))
          .withExposedPorts(3000)
          .withEnv("CHROMIUM_DENY_PUBLIC_IPS", "true")
          .withEnv("CHROMIUM_DENY_PRIVATE_IPS", "true");

  @DynamicPropertySource
  static void gotenbergUrl(DynamicPropertyRegistry registry) {
    registry.add(
        "gotenberg.url",
        () -> "http://" + gotenberg.getHost() + ":" + gotenberg.getMappedPort(3000));
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;

  private final ObjectMapper mapper = new ObjectMapper();

  private UUID createAgreement() {
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

  private ResponseEntity<String> generate(UUID id) {
    return rest.postForEntity("/api/agreements/{id}/document", null, String.class, id);
  }

  private String draftKey(UUID id) {
    return jdbc.queryForObject(
        "SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, id);
  }

  private String contentHash(UUID id) {
    return jdbc.queryForObject(
        "SELECT template_content_hash FROM agreement WHERE id = ?", String.class, id);
  }

  // jsonb reads back as its JSON text through JdbcTemplate on Postgres.
  private String layerVersions(UUID id) {
    return jdbc.queryForObject(
        "SELECT template_layer_versions FROM agreement WHERE id = ?", String.class, id);
  }

  @Test
  void generateStoresARenderedPdfAsTheDraft() {
    UUID id = createAgreement();

    ResponseEntity<String> resp = generate(id);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).contains(id.toString());
    // The draft key is set and the stored blob is a real PDF.
    assertThat(draftKey(id)).isEqualTo("drafts/" + id + ".pdf");
    byte[] stored = blobStore.get("drafts/" + id + ".pdf");
    assertThat(stored.length).isGreaterThan(1000);
    assertThat(new String(stored, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
  }

  @Test
  void generateRecordsTheEffectiveTemplatePinAlongsideTheDraft() {
    UUID id = createAgreement();

    assertThat(generate(id).getStatusCode()).isEqualTo(HttpStatus.OK);

    // The pin is recorded server-side: a content hash and a (non-empty) layerId->version map.
    assertThat(contentHash(id)).isNotBlank();
    assertThat(layerVersions(id)).isNotNull().startsWith("{").isNotEqualTo("{}");
  }

  @Test
  void previewPinsNothing() {
    UUID id = createAgreement();

    ResponseEntity<byte[]> preview =
        rest.getForEntity("/api/agreements/{id}/preview", byte[].class, id);

    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    // A stateless preview renders but stores/pins nothing.
    assertThat(draftKey(id)).isNull();
    assertThat(contentHash(id)).isNull();
    assertThat(layerVersions(id)).isNull();
  }

  @Test
  void regenerateOverwritesWhileUnsigned() {
    UUID id = createAgreement();

    assertThat(generate(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(generate(id).getStatusCode()).isEqualTo(HttpStatus.OK);

    byte[] stored = blobStore.get("drafts/" + id + ".pdf");
    assertThat(new String(stored, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
  }

  @Test
  void generateIsLockedOnceASigningRequestExists() {
    UUID id = createAgreement();
    assertThat(generate(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    byte[] before = blobStore.get("drafts/" + id + ".pdf");

    // Simulate a signing request having been created for this agreement (any state freezes).
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at)"
            + " VALUES (?, ?, 'SIGN_REQUESTED', 0, now())",
        UUID.randomUUID(),
        id);

    ResponseEntity<String> locked = generate(id);
    assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(locked.getBody()).contains("draft-frozen");
    // The stored draft is unchanged.
    assertThat(blobStore.get("drafts/" + id + ".pdf")).isEqualTo(before);
  }

  @Test
  void unknownIdIs404AndNonUuidIs400() {
    assertThat(generate(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<String> badRequest =
        rest.postForEntity("/api/agreements/not-a-uuid/document", null, String.class);
    assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}

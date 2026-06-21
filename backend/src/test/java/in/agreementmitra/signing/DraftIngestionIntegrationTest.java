package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for draft ingestion against real Postgres + MinIO
 * (Testcontainers): the multipart upload endpoint, magic-byte/size validation, the MinIO blob
 * round-trip via {@link BlobStore}, the {@code draft_pdf_key} persistence (V5), and the
 * freeze-once-signing-requested rule. Runs over a real port via {@link TestRestTemplate}; skips
 * (not fails) without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DraftIngestionIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;

  // --- helpers ---------------------------------------------------------------

  private UUID createAgreement() {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "12 MG Road, Bengaluru",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "termMonths", 11,
            "signers",
                List.of(
                    Map.of("name", "Asha Owner", "email", "asha@example.com", "role", "OWNER"),
                    Map.of("name", "Tara Tenant", "email", "tara@example.com", "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private static ByteArrayResource part(String filename, byte[] bytes) {
    return new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  private ResponseEntity<String> upload(UUID agreementId, ByteArrayResource file) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", file);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return rest.postForEntity(
        "/api/agreements/" + agreementId + "/draft", new HttpEntity<>(form, headers), String.class);
  }

  private String draftKey(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, agreementId);
  }

  // --- tests -----------------------------------------------------------------

  @Test
  void validPdfIsStoredInMinioAndKeyPersisted() {
    UUID id = createAgreement();
    byte[] pdf = "%PDF-1.4 a real-ish draft body".getBytes(StandardCharsets.UTF_8);

    ResponseEntity<String> resp = upload(id, part("agreement.pdf", pdf));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    String key = "drafts/" + id + ".pdf";
    assertThat(draftKey(id)).isEqualTo(key);
    assertThat(blobStore.get(key)).isEqualTo(pdf);
    // The response must not echo the stored bytes.
    assertThat(resp.getBody()).doesNotContain("%PDF");
  }

  @Test
  void reuploadBeforeSigningOverwritesTheDraft() {
    UUID id = createAgreement();
    upload(id, part("v1.pdf", "%PDF-1.4 first".getBytes(StandardCharsets.UTF_8)));

    byte[] second = "%PDF-1.4 second and final".getBytes(StandardCharsets.UTF_8);
    ResponseEntity<String> resp = upload(id, part("v2.pdf", second));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(blobStore.get("drafts/" + id + ".pdf")).isEqualTo(second);
  }

  @Test
  void unknownAgreementReturns404ProblemJson() {
    UUID missing = UUID.randomUUID();

    ResponseEntity<String> resp = upload(missing, part("x.pdf", "%PDF-1.4".getBytes()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(resp.getBody()).doesNotContain(missing.toString());
  }

  @Test
  void nonUuidIdReturns400() {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", part("x.pdf", "%PDF-1.4".getBytes()));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/agreements/not-a-uuid/draft", new HttpEntity<>(form, headers), String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).doesNotContain("not-a-uuid");
  }

  @Test
  void nonPdfContentIsRejectedWith400AndStoresNothing() {
    UUID id = createAgreement();

    // Declares PDF content type but the bytes are not a PDF — magic-byte check must win.
    ResponseEntity<String> resp =
        upload(id, part("evil.pdf", "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8)));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(draftKey(id)).isNull();
  }

  @Test
  void oversizedUploadIsRejectedAndStoresNothing() {
    UUID id = createAgreement();
    byte[] huge = new byte[12 * 1024 * 1024]; // over the 10 MiB ceiling and the 11 MiB envelope
    huge[0] = '%';
    huge[1] = 'P';
    huge[2] = 'D';
    huge[3] = 'F';
    huge[4] = '-';

    // The size ceiling rejects this. Tomcat aborts the oversized multipart stream mid-flight, so a
    // real client sees EITHER a clean 400 (small overflow swallowed) OR a transport-level abort
    // (connection reset) — both are valid rejections. The MaxUploadSizeExceededException → 400
    // mapping is asserted precisely in GlobalExceptionHandlerTest. The security-critical property
    // here: nothing is stored.
    try {
      ResponseEntity<String> resp = upload(id, part("big.pdf", huge));
      assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    } catch (org.springframework.web.client.ResourceAccessException abortedByServer) {
      // Connection reset on the oversized stream — also a rejection.
    }
    assertThat(draftKey(id)).isNull();
  }

  @Test
  void uploadIsFrozenOnceASigningRequestExists() {
    UUID id = createAgreement();
    upload(id, part("v1.pdf", "%PDF-1.4 original".getBytes(StandardCharsets.UTF_8)));

    // Simulate a signing request having been created for this agreement (any state freezes).
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at)"
            + " VALUES (?, ?, 'SIGN_REQUESTED', 0, now())",
        UUID.randomUUID(),
        id);

    ResponseEntity<String> resp =
        upload(id, part("v2.pdf", "%PDF-1.4 attempted change".getBytes(StandardCharsets.UTF_8)));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).contains("draft-frozen");
    // The original draft is unchanged.
    assertThat(new String(blobStore.get("drafts/" + id + ".pdf"), StandardCharsets.UTF_8))
        .isEqualTo("%PDF-1.4 original");
  }

  @Test
  void frozenAfterTerminalSigningRequestToo() {
    UUID id = createAgreement();
    upload(id, part("v1.pdf", "%PDF-1.4 original".getBytes(StandardCharsets.UTF_8)));
    // A terminal (FAILED) request still finalizes the draft in v1 — no free re-upload after a paid
    // eSign attempt (revision is a future, paid flow).
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at)"
            + " VALUES (?, ?, 'FAILED', 0, now())",
        UUID.randomUUID(),
        id);

    ResponseEntity<String> resp =
        upload(id, part("v2.pdf", "%PDF-1.4 retry".getBytes(StandardCharsets.UTF_8)));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void flywayV5DraftMigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }
}

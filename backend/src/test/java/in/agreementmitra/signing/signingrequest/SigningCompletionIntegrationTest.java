package in.agreementmitra.signing.signingrequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for signing completion against real Postgres + MinIO (Testcontainers) and a
 * stubbed Leegality (WireMock): the download-on-SIGNED artifact storage (keys in Postgres, bytes in
 * MinIO), idempotency under re-delivery, the recoverable failed-download, and the reconciliation
 * job (invoked directly; the scheduler is deferred). No live sandbox account.
 *
 * <p>Reconciliation is enabled here (the bean must exist) but its first scheduled run is pushed an
 * hour out so it never fires during the test; behaviour is exercised via {@link
 * SigningReconciliationJob#reconcile()} directly. Eligibility thresholds are zeroed so freshly
 * created rows qualify immediately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "signing.reconciliation.enabled=true",
      "signing.reconciliation.initial-delay=PT1H",
      "signing.reconciliation.interval=PT1H",
      "signing.reconciliation.age-threshold=PT0S",
      "signing.reconciliation.grace=PT0S"
    })
class SigningCompletionIntegrationTest {

  private static final String AUTH_TOKEN = "it-auth-token";
  private static final String WEBHOOK_MAC_KEY = "it-mac-key-value";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void leegalityProperties(DynamicPropertyRegistry registry) {
    registry.add("esign.leegality.base-url", () -> WIREMOCK.baseUrl() + "/api/");
    registry.add("esign.leegality.auth-token", () -> AUTH_TOKEN);
    registry.add("esign.leegality.webhook-secret", () -> WEBHOOK_MAC_KEY);
    registry.add("esign.leegality.profile-id", () -> "it-profile");
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;
  @Autowired private SigningReconciliationJob reconciliationJob;

  @BeforeEach
  void resetStubs() {
    WIREMOCK.resetAll();
  }

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
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    uploadDraft(id); // signing now requires an uploaded draft (CR-5)
    return id;
  }

  private void uploadDraft(UUID agreementId) {
    var form = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(
            "%PDF-1.4 completion draft".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void stubCreate(String documentId) {
    WIREMOCK.stubFor(
        post(urlEqualTo("/api/v3.0/sign/request"))
            .willReturn(
                okJson(
                    "{\"status\":\"SUCCESS\",\"data\":{\"documentId\":\""
                        + documentId
                        + "\",\"invitees\":[{\"inviteeId\":\"INV-1\",\"signUrl\":\"https://sign/1\",\"expiryDate\":\"2026-01-01\"},"
                        + "{\"inviteeId\":\"INV-2\",\"signUrl\":\"https://sign/2\",\"expiryDate\":\"2026-01-02\"}]}}")));
  }

  /** Details with both invitees SIGNED, and (optionally) artifact URLs for the download step. */
  private void stubDetailsSignedWithArtifacts(boolean withArtifacts) {
    String document =
        withArtifacts
            ? "{\"status\":\"COMPLETED\",\"signedUrl\":\""
                + WIREMOCK.baseUrl()
                + "/files/signed.pdf\",\"auditTrailUrl\":\""
                + WIREMOCK.baseUrl()
                + "/files/audit.bin\"}"
            : "{\"status\":\"COMPLETED\"}";
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .willReturn(
                okJson(
                    "{\"data\":{\"document\":"
                        + document
                        + ",\"invitees\":[{\"inviteeId\":\"INV-1\",\"status\":\"SIGNED\"},"
                        + "{\"inviteeId\":\"INV-2\",\"status\":\"SIGNED\"}]}}")));
    if (withArtifacts) {
      WIREMOCK.stubFor(
          get(urlPathEqualTo("/files/signed.pdf"))
              .willReturn(aResponse().withBody("PDFBYTES".getBytes(StandardCharsets.UTF_8))));
      WIREMOCK.stubFor(
          get(urlPathEqualTo("/files/audit.bin"))
              .willReturn(aResponse().withBody("AUDIT".getBytes(StandardCharsets.UTF_8))));
    }
  }

  private UUID createSigningRequest(UUID agreementId, String documentId) {
    stubCreate(documentId);
    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(
        jdbc.queryForObject(
            "SELECT id::text FROM signing_request WHERE provider_document_id = ?",
            String.class,
            documentId));
  }

  private ResponseEntity<String> postWebhook(String documentId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        "{\"documentId\":\""
            + documentId
            + "\",\"mac\":\""
            + hmacSha1Hex(documentId, WEBHOOK_MAC_KEY)
            + "\"}";
    return rest.postForEntity("/api/webhooks/esign", new HttpEntity<>(body, headers), String.class);
  }

  private static String hmacSha1Hex(String data, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : raw) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String keyOf(UUID id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM signing_request WHERE id = ?", String.class, id);
  }

  // --- tests -----------------------------------------------------------------

  @Test
  void signedWebhookStoresArtifactsKeysInPostgresBytesInMinio() {
    UUID agreementId = createAgreement();
    UUID id = createSigningRequest(agreementId, "DOC-DL-1");
    stubDetailsSignedWithArtifacts(true);

    assertThat(postWebhook("DOC-DL-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE id = ?", String.class, id))
        .isEqualTo("SIGNED");
    String pdfKey = keyOf(id, "signed_pdf_key");
    String auditKey = keyOf(id, "audit_trail_key");
    assertThat(pdfKey).isEqualTo("signed/" + id + ".pdf");
    assertThat(auditKey).isEqualTo("audit/" + id);
    assertThat(new String(blobStore.get(pdfKey), StandardCharsets.UTF_8)).isEqualTo("PDFBYTES");
    assertThat(new String(blobStore.get(auditKey), StandardCharsets.UTF_8)).isEqualTo("AUDIT");
  }

  @Test
  void redeliveredSignedWebhookDoesNotChangeKeys() {
    UUID agreementId = createAgreement();
    UUID id = createSigningRequest(agreementId, "DOC-DL-2");
    stubDetailsSignedWithArtifacts(true);

    postWebhook("DOC-DL-2");
    String firstKey = keyOf(id, "signed_pdf_key");
    postWebhook("DOC-DL-2");

    assertThat(keyOf(id, "signed_pdf_key")).isEqualTo(firstKey);
  }

  @Test
  void failedDownloadLeavesNullKeysThenReconciliationRecovers() {
    UUID agreementId = createAgreement();
    UUID id = createSigningRequest(agreementId, "DOC-DL-3");

    // Details reports SIGNED but exposes no artifact URL → download fails; row reaches SIGNED with
    // null keys (recoverable), the webhook is still acked.
    stubDetailsSignedWithArtifacts(false);
    assertThat(postWebhook("DOC-DL-3").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE id = ?", String.class, id))
        .isEqualTo("SIGNED");
    assertThat(keyOf(id, "signed_pdf_key")).isNull();

    // Now the artifacts are available; reconciliation re-attempts the download and records keys.
    stubDetailsSignedWithArtifacts(true);
    reconciliationJob.reconcile();

    assertThat(keyOf(id, "signed_pdf_key")).isEqualTo("signed/" + id + ".pdf");
    assertThat(new String(blobStore.get(keyOf(id, "signed_pdf_key")), StandardCharsets.UTF_8))
        .isEqualTo("PDFBYTES");
  }

  @Test
  void reconciliationDoesNotSweepPdfGeneratedOrphans() {
    // A PDF_GENERATED orphan (provider failed at create) — no document id, cannot be reconciled.
    UUID orphanId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, provider_document_id, status, version, created_at)"
            + " VALUES (?, ?, NULL, 'PDF_GENERATED', 0, ?)",
        orphanId,
        createAgreement(),
        Timestamp.from(Instant.now().minusSeconds(3600)));

    reconciliationJob.reconcile();

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE id = ?", String.class, orphanId))
        .isEqualTo("PDF_GENERATED");
  }

  @Test
  void perInviteeStatusOrdinalAndProviderIdArePersisted() {
    UUID agreementId = createAgreement();
    UUID id = createSigningRequest(agreementId, "DOC-PI-1");
    // One invitee signed, the other still pending → request stays SIGN_REQUESTED, but the per-row
    // status is persisted, correlated by provider invitee id.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .willReturn(
                okJson(
                    "{\"data\":{\"invitees\":[{\"inviteeId\":\"INV-1\",\"status\":\"SIGNED\"},"
                        + "{\"inviteeId\":\"INV-2\",\"status\":\"SENT\"}]}}")));

    postWebhook("DOC-PI-1");

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE id = ?", String.class, id))
        .isEqualTo("SIGN_REQUESTED");
    // signing_order populated for both rows (0,1); provider_invitee_id captured at create.
    Long ordered =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signing_request_invitee WHERE signing_request_id = ?"
                + " AND signing_order IS NOT NULL AND provider_invitee_id IS NOT NULL",
            Long.class,
            id);
    assertThat(ordered).isEqualTo(2);
    String inv1Status =
        jdbc.queryForObject(
            "SELECT status FROM signing_request_invitee WHERE signing_request_id = ?"
                + " AND provider_invitee_id = 'INV-1'",
            String.class,
            id);
    assertThat(inv1Status).isEqualTo("SIGNED");
  }

  @Test
  void duplicateSigningOrderIsRejectedByUniqueConstraint() {
    UUID agreementId = createAgreement();
    UUID id = createSigningRequest(agreementId, "DOC-PI-2");
    // Reuse an existing signer (FK satisfied) so the failure is specifically the ordinal collision.
    UUID signerId =
        UUID.fromString(
            jdbc.queryForObject(
                "SELECT signer_id::text FROM signing_request_invitee WHERE signing_request_id = ? LIMIT 1",
                String.class,
                id));
    // Inserting a second invitee row with an ordinal that already exists (0) violates
    // UNIQUE(signing_request_id, signing_order).
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO signing_request_invitee (id, signing_request_id, signer_id, sign_url, signing_order)"
                        + " VALUES (?, ?, ?, 'https://x', 0)",
                    UUID.randomUUID(),
                    id,
                    signerId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(
            "uq_sri_request_order"); // the ordinal UNIQUE, not some other constraint
  }

  @Test
  void flywayV4MigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }
}

package in.agreementmitra.signing.signingrequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import in.agreementmitra.support.Payments;
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

  /**
   * Pin every agreement these tests create to an ELIGIBLE jurisdiction. Since
   * jurisdiction-checkout-gating, an agreement with no pinned template has no duty jurisdiction and
   * is refused at finalise, checkout, e-stamp intake and eSign initiation - so a fixture that
   * creates a bare agreement can no longer reach the steps these tests exercise. The seeder is
   * local/sandbox-only, so the row is inserted here.
   */
  @BeforeEach
  void seedEligibleTemplate() {
    in.agreementmitra.support.TemplateCatalogFixture.seedEligible(jdbc);
  }

  @Autowired private BlobStore blobStore;
  @Autowired private SigningReconciliationJob reconciliationJob;
  @Autowired private in.agreementmitra.identity.IdentityService identityService;
  @Autowired private in.agreementmitra.identity.oauth.HandoffService handoffService;
  @Autowired private in.agreementmitra.identity.session.SessionService sessionService;

  private String staffToken;

  @BeforeEach
  void resetStubs() {
    WIREMOCK.resetAll();
    staffToken =
        in.agreementmitra.support.StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "completion-staff-" + UUID.randomUUID());
  }

  // --- helpers ---------------------------------------------------------------

  private UUID createAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "12 MG Road, Bengaluru",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    Map.of(
                        "firstName",
                        "Asha",
                        "lastName",
                        "Owner",
                        "fatherName",
                        "Ravi Owner",
                        "currentAddress",
                        "1 A St",
                        "email",
                        "asha@example.com",
                        "role",
                        "OWNER"),
                    Map.of(
                        "firstName",
                        "Tara",
                        "lastName",
                        "Tenant",
                        "fatherName",
                        "Hari Tenant",
                        "currentAddress",
                        "3 C St",
                        "email",
                        "tara@example.com",
                        "role",
                        "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    uploadDraft(id); // signing requires an uploaded draft (CR-5)
    finalise(id); // ...the customer finalises, which places the order (PDF_GENERATED)
    uploadStamp(id); // ...and staff attach the purchased e-stamp
    return id;
  }

  /**
   * Place the order the way the customer does; this is what creates the PDF_GENERATED request.
   *
   * <p>The payment gate ships {@code REQUIRED}, so the order is taken past it here too - this file
   * is about webhook verification, completion, and reconciliation, not about payment. The gate is
   * still evaluated at signing initiation below; it simply passes.
   */
  private void finalise(UUID agreementId) {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Payments.waive(jdbc, agreementId);
  }

  /**
   * Perform the staff e-stamp upload over HTTP. Stamping is no longer automatic: signing refuses
   * with 409 until a staff member has attached a purchased certificate.
   */
  private void uploadStamp(UUID agreementId) {
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
    var form = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    form.add(
        "scan",
        new org.springframework.core.io.ByteArrayResource(
            in.agreementmitra.support.TestImages.certificateScan()) {
          @Override
          public String getFilename() {
            return "certificate.png";
          }
        });
    form.add("agreementReference", reference);
    form.add(
        "certificateNumber",
        "IN-KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase());
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", "500.00");
    form.add("jurisdiction", "KA");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(staffToken);
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/staff/estamp",
            org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void uploadDraft(UUID agreementId) {
    var form = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(
            in.agreementmitra.support.TestPdfs.singlePage()) {
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
  void reconciliationDoesNotDeliverPreExistingSignedAgreementsThatAlreadyHoldTheirArtifacts() {
    // THE DEPLOY HAZARD, PINNED (signed-delivery-and-closure, Migration Plan step 3). V18 adds
    // delivery deliberately WITHOUT a backfill, so agreements that completed before delivery
    // existed must stay open and silent. The claim rests entirely on the reconciliation scan's
    // selection: it takes SIGNED rows only while `signed_pdf_key IS NULL`, so a pre-existing SIGNED
    // agreement that already downloaded its artifacts is never re-entered, never grows delivery
    // records, and therefore cannot be emailed. Were that predicate ever widened, this deploy would
    // emit a burst of legal documents to real parties - which is why it is a test and not a
    // sentence in a document.
    UUID agreementId = createAgreement();
    UUID requestId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, provider_document_id, status,"
            + " signed_pdf_key, audit_trail_key, version, created_at)"
            + " VALUES (?, ?, 'DOC-PRE-EXISTING', 'SIGNED', ?, ?, 0, ?)",
        requestId,
        agreementId,
        "signed/" + requestId + ".pdf",
        "audit/" + requestId + ".bin",
        Timestamp.from(Instant.now().minusSeconds(3600)));
    // Stub Details as available, so a selected row would complete rather than die on a missing
    // stub. The assertion below is on NON-SELECTION: zero calls to Details means the scan never
    // picked this row up, which is the actual claim. Asserting only "no delivery rows appeared"
    // would pass vacuously, since a pre-existing agreement has no invitee rows to deliver to.
    stubDetailsSignedWithArtifacts(true);

    reconciliationJob.reconcile();

    // Scoped to THIS document id: reconcile() sweeps globally, so rows left by sibling tests in
    // this class legitimately reach Details and an unscoped count would be order-dependent.
    WIREMOCK.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v3.3/document/details"))
            .withQueryParam("documentId", equalTo("DOC-PRE-EXISTING")));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signed_document_delivery WHERE agreement_id = ?",
                Integer.class,
                agreementId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT closure_state FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("OPEN");
  }

  @Test
  void reconciliationDoesNotSweepStampFailed() {
    // A STAMP_FAILED row is terminal and must never be picked up by the reconciliation scan
    // (which selects only stale SIGN_REQUESTED and SIGNED-with-null-keys).
    UUID stampFailedId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, provider_document_id, status, version, created_at)"
            + " VALUES (?, ?, NULL, 'STAMP_FAILED', 0, ?)",
        stampFailedId,
        createAgreement(),
        Timestamp.from(Instant.now().minusSeconds(3600)));

    reconciliationJob.reconcile();

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE id = ?", String.class, stampFailedId))
        .isEqualTo("STAMP_FAILED");
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

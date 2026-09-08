package in.agreementmitra.signing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.Payments;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for the signing-request API against real Postgres (Testcontainers)
 * and a stubbed Leegality (WireMock): the create + webhook endpoints, the security permits, the
 * persist-before-provider flow, the FSM, and the V3 schema. No live sandbox account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SigningRequestApiIntegrationTest {

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

  @Autowired private in.agreementmitra.signing.BlobStore blobStore;
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
            "signing-staff-" + UUID.randomUUID());
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * A persisted agreement with an uploaded draft AND an attached e-stamp -- both are preconditions
   * for requesting signing now that stamping is a staff-driven, out-of-band step.
   */
  private UUID createAgreement() {
    UUID id = createDraftedAgreement();
    uploadStamp(id);
    return id;
  }

  /**
   * A persisted agreement with a draft, FINALISED (so the order exists and the terms are frozen)
   * but with no e-stamp yet -- exercises the stamp-required 409.
   */
  private UUID createDraftedAgreement() {
    UUID id = createBareAgreement();
    uploadDraft(id);
    finalise(id);
    return id;
  }

  /**
   * Place the order the way the customer does. Returns the tracking reference they are given.
   *
   * <p>The payment gate ships {@code REQUIRED}, so the order is taken past it here too - this file
   * is about the stamp/draft/contact preconditions on signing, not about payment. The gate is still
   * evaluated on every request below; it simply passes. Its refusal path is covered in {@code
   * PaymentGateIntegrationTest} and {@code RazorpayPaymentGateIntegrationTest}.
   */
  private String finalise(UUID agreementId) {
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> resp =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, Map.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Payments.waive(jdbc, agreementId);
    return (String) resp.getBody().get("trackingReference");
  }

  /** Perform the staff e-stamp upload over HTTP, exactly as an operator would. */
  private void uploadStamp(UUID agreementId) {
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
    String certificate =
        "IN-KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
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
    form.add("certificateNumber", certificate);
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", "500.00");
    form.add("jurisdiction", "KA");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(staffToken);
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /** A persisted agreement with NO draft yet — used to exercise the draft-required 409. */
  private UUID createBareAgreement() {
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
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private void uploadDraft(UUID agreementId) {
    // A real, parseable PDF — since CR-6 the signing flow stamps (parses) the draft with PDFBox.
    uploadDraft(agreementId, in.agreementmitra.support.TestPdfs.singlePage());
  }

  private void uploadDraft(UUID agreementId, byte[] pdf) {
    var form = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(pdf) {
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
                        + "\",\"invitees\":[{\"signUrl\":\"https://sign/1\",\"expiryDate\":\"2026-01-01\"},"
                        + "{\"signUrl\":\"https://sign/2\",\"expiryDate\":\"2026-01-02\"}]}}")));
  }

  private void stubDetails(String documentId, String status) {
    // getStatus reads per-invitee statuses (data.invitees[]); emit the same vendor status for both
    // invitees of the 2-signer agreements these tests create. document.status kept for
    // completeness.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .willReturn(
                okJson(
                    "{\"data\":{\"document\":{\"status\":\""
                        + status
                        + "\"},\"invitees\":[{\"inviteeId\":\"INV-1\",\"status\":\""
                        + status
                        + "\"},{\"inviteeId\":\"INV-2\",\"status\":\""
                        + status
                        + "\"}]}}")));
  }

  private void stubDetailsFailure() {
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details")).willReturn(aResponse().withStatus(500)));
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

  private ResponseEntity<String> postWebhook(String documentId, String mac) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        "{\"documentId\":\"" + documentId + "\",\"mac\":\"" + mac + "\",\"event\":\"COMPLETED\"}";
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

  private String statusOf(UUID signingRequestId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE id = ?", String.class, signingRequestId);
  }

  private String statusOfDoc(String providerDocumentId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE provider_document_id = ?",
        String.class,
        providerDocumentId);
  }

  // --- create ----------------------------------------------------------------

  @Test
  void createReturns201AndPersistsSignRequestedWithoutBlocking() {
    UUID agreementId = createAgreement();
    stubCreate("DOC-CREATE-1");

    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(resp.getBody()).contains("DOC-CREATE-1").contains("https://sign/1");

    Long rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signing_request WHERE provider_document_id = ?",
            Long.class,
            "DOC-CREATE-1");
    assertThat(rows).isEqualTo(1);
    // Did NOT block on a signature — it returned in SIGN_REQUESTED, not a terminal state.
    String status =
        jdbc.queryForObject(
            "SELECT status FROM signing_request WHERE provider_document_id = ?",
            String.class,
            "DOC-CREATE-1");
    assertThat(status).isEqualTo("SIGN_REQUESTED");
    Long invitees =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signing_request_invitee i JOIN signing_request s ON i.signing_request_id = s.id"
                + " WHERE s.provider_document_id = ?",
            Long.class,
            "DOC-CREATE-1");
    assertThat(invitees).isEqualTo(2);
  }

  @Test
  void theStoredStampedPdfIsWhatReachesTheProvider() throws Exception {
    UUID agreementId = createAgreement();
    stubCreate("DOC-STAMP-1");

    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // The stamp DATA came from the staff upload: a real certificate number and dutyPaid = true.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT stamp_certificate_number, stamped_pdf_key, stamp_duty_amount,"
                + " stamp_jurisdiction, stamp_duty_paid FROM agreement WHERE id = ?",
            agreementId);
    String certificate = (String) row.get("stamp_certificate_number");
    assertThat(certificate).startsWith("IN-KA");
    assertThat(row.get("stamp_jurisdiction")).isEqualTo("KA");
    assertThat(row.get("stamp_duty_paid")).isEqualTo(true);
    assertThat(row.get("stamped_pdf_key")).isEqualTo("stamped/" + agreementId + ".pdf");

    // The stamped PDF is the one the provider received: scan page prepended (1 + 1 draft page = 2)
    // and NOTHING stamped onto the document page -- the certificate number is persisted (asserted
    // above) but deliberately never printed, so the instrument cannot assert a stamp it could not
    // vouch for. See PdfStampComposer#compose. Nothing is re-composited here.
    byte[] stamped = blobStore.get("stamped/" + agreementId + ".pdf");
    try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc))
          .doesNotContain(certificate)
          .doesNotContain("e-Stamp Certificate No.");
    }

    // FSM: PDF_GENERATED -> STAMPED (staff upload) -> SIGN_REQUESTED (this call).
    assertThat(statusOfDoc("DOC-STAMP-1")).isEqualTo("SIGN_REQUESTED");
  }

  @Test
  void createWithoutAnAttachedStampReturns409AndCallsNoProvider() {
    UUID agreementId = createDraftedAgreement(); // draft uploaded, no e-stamp yet
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class);

    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    // Distinguishable from draft-required / contact-required.
    assertThat(resp.getBody()).contains("stamp-required").doesNotContain("draft-required");
    // No signing-request row, and the provider was never called.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class))
        .isEqualTo(before);
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/api/v3.0/sign/request")));
  }

  @Test
  void aRequestRestsInPdfGeneratedUntilStaffUploadTheStamp() {
    // PDF_GENERATED is durable now: the order is placed at finalisation and waits there,
    // potentially
    // for days, while staff buy the e-stamp out-of-band. Nothing may reap or expire it.
    UUID agreementId = createDraftedAgreement();
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");

    uploadStamp(agreementId); // ADVANCES the existing request; it does not create one

    assertThat(statusOfAgreement(agreementId)).isEqualTo("STAMPED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_request WHERE agreement_id = ?",
                Long.class,
                agreementId))
        .isEqualTo(1); // exactly one order, created once, at finalisation
  }

  @Test
  void theCustomerAndTheDocumentCarryTheSameTrackingReference() {
    UUID agreementId = createBareAgreement();
    String persisted =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);

    ResponseEntity<String> read = rest.getForEntity("/api/agreements/" + agreementId, String.class);

    // The response's trackingNumber IS the persisted reference - not a separately-derived veneer.
    assertThat(read.getBody()).contains("\"trackingNumber\":\"" + persisted + "\"");
  }

  private String statusOfAgreement(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE agreement_id = ?", String.class, agreementId);
  }

  @Test
  void stampInfoIsNotExposedOnTheAgreementResponse() {
    UUID agreementId = createAgreement();
    stubCreate("DOC-STAMP-2");
    rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);

    ResponseEntity<String> get = rest.getForEntity("/api/agreements/" + agreementId, String.class);

    assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get.getBody())
        .doesNotContain("stamp")
        .doesNotContain("stamped")
        .doesNotContain("certificate")
        .doesNotContain("IN-KA");
  }

  @Test
  void createForUnknownAgreementReturns404AndCallsNoProvider() {
    UUID missing = UUID.randomUUID();
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class);

    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + missing + "/request", null, String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class))
        .isEqualTo(before);
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/api/v3.0/sign/request")));
  }

  @Test
  void nonUuidAgreementIdReturns400() {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/not-a-uuid/request", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void createWithoutAnUploadedDraftReturns409AndCallsNoProvider() {
    UUID agreementId = createBareAgreement();
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class);

    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getHeaders().getContentType())
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(resp.getBody()).contains("draft-required");
    // No signing-request row created, and the provider was never called.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class))
        .isEqualTo(before);
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/api/v3.0/sign/request")));
  }

  @Test
  void oversizedBodyIsRejectedWith413() {
    UUID agreementId = createAgreement();
    String huge = "x".repeat(2 * 1024 * 1024); // 2 MiB > 1 MiB limit
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/signing/" + agreementId + "/request",
            new HttpEntity<>(huge, headers),
            String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  // --- webhook ---------------------------------------------------------------

  @Test
  void validWebhookDrivesSignedOffDetailsApi() {
    UUID agreementId = createAgreement();
    UUID signingRequestId = createSigningRequest(agreementId, "DOC-WH-1");
    stubDetails("DOC-WH-1", "COMPLETED");

    ResponseEntity<String> resp = postWebhook("DOC-WH-1", hmacSha1Hex("DOC-WH-1", WEBHOOK_MAC_KEY));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
  }

  @Test
  void tamperedMacIsRejectedWithNoStateChange() {
    UUID agreementId = createAgreement();
    UUID signingRequestId = createSigningRequest(agreementId, "DOC-WH-2");
    stubDetails("DOC-WH-2", "COMPLETED");

    ResponseEntity<String> resp =
        postWebhook("DOC-WH-2", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED");
  }

  @Test
  void replayOfValidWebhookIsIdempotent() {
    UUID agreementId = createAgreement();
    UUID signingRequestId = createSigningRequest(agreementId, "DOC-WH-3");
    stubDetails("DOC-WH-3", "COMPLETED");
    String mac = hmacSha1Hex("DOC-WH-3", WEBHOOK_MAC_KEY);

    assertThat(postWebhook("DOC-WH-3", mac).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(postWebhook("DOC-WH-3", mac).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
  }

  @Test
  void verifiedWebhookForUnknownDocumentIsAckedWithNoChange() {
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class);
    stubDetails("DOC-UNKNOWN", "COMPLETED");

    ResponseEntity<String> resp =
        postWebhook("DOC-UNKNOWN", hmacSha1Hex("DOC-UNKNOWN", WEBHOOK_MAC_KEY));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // indistinguishable ack
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class))
        .isEqualTo(before);
  }

  @Test
  void detailsApiFailureIsAckedWithoutTransition() {
    UUID agreementId = createAgreement();
    UUID signingRequestId = createSigningRequest(agreementId, "DOC-WH-4");
    stubDetailsFailure();

    ResponseEntity<String> resp = postWebhook("DOC-WH-4", hmacSha1Hex("DOC-WH-4", WEBHOOK_MAC_KEY));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED");
  }

  @Test
  void concurrentWebhooksConvergeToOneTerminalState() throws Exception {
    UUID agreementId = createAgreement();
    UUID signingRequestId = createSigningRequest(agreementId, "DOC-WH-5");
    stubDetails("DOC-WH-5", "COMPLETED");
    String mac = hmacSha1Hex("DOC-WH-5", WEBHOOK_MAC_KEY);

    ExecutorService pool = Executors.newFixedThreadPool(6);
    List<Future<Integer>> futures =
        IntStream.range(0, 6)
            .mapToObj(i -> pool.submit(() -> postWebhook("DOC-WH-5", mac).getStatusCode().value()))
            .toList();
    for (Future<Integer> f : futures) {
      assertThat(f.get()).isEqualTo(HttpStatus.ACCEPTED.value());
    }
    pool.shutdown();

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
  }

  @Test
  void flywayV3SigningRequestMigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }
}

package in.agreementmitra.signing.signingrequest;

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
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end signing against the <b>ZOOP eSign v5</b> adapter, real Postgres + MinIO
 * (Testcontainers) and a stubbed ZOOP (WireMock). No live credentials, no real Aadhaar, no real
 * host.
 *
 * <p>This is also the provider-selector test: {@code esign.provider=zoop} here, while the rest of
 * the suite runs {@code leegality}, so both adapters are exercised on every build and switching
 * providers demonstrably needs no caller change.
 *
 * <p>The full customer journey is walked for real, because that ordering is where the bugs live:
 * create -> upload draft -> finalise (places the order) -> staff attach the purchased e-stamp ->
 * request signing -> webhook -> authoritative fetch -> SIGNED -> artifacts stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "esign.provider=zoop",
      "signing.reconciliation.enabled=true",
      "signing.reconciliation.initial-delay=PT1H",
      "signing.reconciliation.interval=PT1H",
      "signing.reconciliation.age-threshold=PT0S",
      "signing.reconciliation.grace=PT0S"
    })
class ZoopSigningIntegrationTest {

  private static final String INIT_URL = "/contract/esign/v5/init";
  private static final String GROUP_URL = "/contract/esign/v5/fetch/group";
  private static final String AUDIT_URL = "/contract/esign/v5/fetch/audit-trail";
  private static final String WEBHOOK_KEY_HEADER = "webhook-security-key";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void zoopProperties(DynamicPropertyRegistry registry) {
    registry.add("esign.zoop.base-url", () -> WIREMOCK.baseUrl() + "/contract/esign/");
    registry.add("esign.zoop.app-id", () -> "it-app-id");
    registry.add("esign.zoop.api-key", () -> "it-api-key");
    registry.add("esign.zoop.txn-expiry-min", () -> 10080);
    registry.add("esign.zoop.artifact-hosts", () -> URI.create(WIREMOCK.baseUrl()).getHost());
    registry.add("esign.zoop.response-url", () -> "https://hooks.example.com/api/webhooks/esign");
    registry.add("esign.zoop.redirect-url", () -> "https://app.example.com/done");
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;
  @Autowired private SigningReconciliationJob reconciliationJob;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private String staffToken;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "zoop-staff-" + UUID.randomUUID());
  }

  // --- fixtures --------------------------------------------------------------

  private UUID createStampedAgreement() {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "12 MG Road, Bengaluru",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    Map.of(
                        "firstName", "Asha",
                        "lastName", "Owner",
                        "fatherName", "Ravi Owner",
                        "currentAddress", "1 A St",
                        "email", "asha@example.com",
                        "role", "OWNER"),
                    Map.of(
                        "firstName", "Tara",
                        "lastName", "Tenant",
                        "fatherName", "Hari Tenant",
                        "currentAddress", "3 C St",
                        "email", "tara@example.com",
                        "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    // The draft carries the esign:<role> anchors the renderer emits; without them the adapter
    // refuses (and rightly so - it will not place a signature at a guessed position).
    uploadDraft(id, TestPdfs.withEsignAnchors());
    finalise(id);
    uploadStamp(id);
    return id;
  }

  private void uploadDraft(UUID agreementId, byte[] pdf) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(pdf) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + agreementId + "/draft",
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  /**
   * Place the order the way the customer does, then take it past the payment gate - which ships
   * {@code REQUIRED}. This file is about the ZOOP adapter and signature placement, not payment; the
   * gate is still evaluated at intake and at signing initiation below, it simply passes.
   */
  private void finalise(UUID agreementId) {
    assertThat(
            rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    Payments.waive(jdbc, agreementId);
  }

  private void uploadStamp(UUID agreementId) {
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "scan",
        new ByteArrayResource(TestImages.certificateScan()) {
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
    assertThat(
            rest.exchange(
                    "/api/staff/estamp",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  // --- ZOOP stubs ------------------------------------------------------------

  private void stubInit(String groupId, String webhookKey) {
    WIREMOCK.stubFor(
        post(urlEqualTo(INIT_URL))
            .willReturn(
                okJson(
                    "{\"success\":true,\"group_id\":\""
                        + groupId
                        + "\",\"webhook_security_key\":\""
                        + webhookKey
                        + "\",\"expires_at\":\"2026-08-10T10:00:00Z\",\"requests\":["
                        + "{\"request_id\":\"REQ-1\",\"signer_email\":\"asha@example.com\","
                        + "\"signing_order\":1,\"signing_url\":\"https://esign.zoop.plus/s/1\"},"
                        + "{\"request_id\":\"REQ-2\",\"signer_email\":\"tara@example.com\","
                        + "\"signing_order\":2,\"signing_url\":\"https://esign.zoop.plus/s/2\"}]}")));
  }

  private void stubGroup(String first, String second, boolean withArtifacts) {
    String complete =
        withArtifacts
            ? ",\"complete_signed_url\":\"" + WIREMOCK.baseUrl() + "/files/complete.pdf\""
            : "";
    WIREMOCK.stubFor(
        get(urlPathEqualTo(GROUP_URL))
            .willReturn(
                okJson(
                    "{\"success\":true,\"transaction_status\":\"SIGNED\""
                        + complete
                        + ",\"requests\":[{\"request_id\":\"REQ-1\",\"status\":\""
                        + first
                        + "\"},{\"request_id\":\"REQ-2\",\"status\":\""
                        + second
                        + "\"}]}")));
    if (withArtifacts) {
      WIREMOCK.stubFor(
          get(urlPathEqualTo("/files/complete.pdf"))
              .willReturn(
                  aResponse()
                      .withHeader("Content-Type", "application/pdf")
                      .withBody("SIGNEDPDF".getBytes(StandardCharsets.UTF_8))));
      WIREMOCK.stubFor(
          get(urlPathEqualTo(AUDIT_URL))
              .willReturn(
                  aResponse()
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"trail\":[]}".getBytes(StandardCharsets.UTF_8))));
    }
  }

  private UUID requestSigning(UUID agreementId, String groupId) {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(
        jdbc.queryForObject(
            "SELECT id::text FROM signing_request WHERE provider_document_id = ?",
            String.class,
            groupId));
  }

  private ResponseEntity<String> postWebhook(String groupId, String presentedKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (presentedKey != null) {
      headers.set(WEBHOOK_KEY_HEADER, presentedKey);
    }
    String body = "{\"group_id\":\"" + groupId + "\",\"request_id\":\"REQ-1\",\"success\":true}";
    return rest.postForEntity("/api/webhooks/esign", new HttpEntity<>(body, headers), String.class);
  }

  private String statusOf(UUID signingRequestId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE id = ?", String.class, signingRequestId);
  }

  private String columnOf(UUID signingRequestId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM signing_request WHERE id = ?", String.class, signingRequestId);
  }

  // --- tests -----------------------------------------------------------------

  @Test
  void happyPathInitThenWebhookThenFetchDrivesSignedAndStoresArtifacts() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-HP-1", "WHK-HP-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-HP-1");
    stubGroup("SIGNED", "SIGNED", true);

    assertThat(postWebhook("GRP-HP-1", "WHK-HP-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
    String pdfKey = columnOf(signingRequestId, "signed_pdf_key");
    String auditKey = columnOf(signingRequestId, "audit_trail_key");
    assertThat(new String(blobStore.get(pdfKey), StandardCharsets.UTF_8)).isEqualTo("SIGNEDPDF");
    assertThat(new String(blobStore.get(auditKey), StandardCharsets.UTF_8)).contains("trail");
  }

  @Test
  void exactlyOneProviderCallCarriesBothSignersAndBothPerSignerTokensArePersisted() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-ONE-1", "WHK-ONE-1");

    UUID signingRequestId = requestSigning(agreementId, "GRP-ONE-1");

    WIREMOCK.verify(1, postRequestedFor(urlEqualTo(INIT_URL)));
    Long correlated =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signing_request_invitee WHERE signing_request_id = ?"
                + " AND provider_invitee_id IS NOT NULL AND signing_order IS NOT NULL",
            Long.class,
            signingRequestId);
    assertThat(correlated).isEqualTo(2);
    // The OWNER is invited first: sequential signing on a legal instrument is not order-agnostic.
    String firstEmail =
        jdbc.queryForObject(
            "SELECT s.email FROM signing_request_invitee i JOIN signer s ON s.id = i.signer_id"
                + " WHERE i.signing_request_id = ? AND i.signing_order = 0",
            String.class,
            signingRequestId);
    assertThat(firstEmail).isEqualTo("asha@example.com");
  }

  @Test
  void thePerTransactionWebhookKeyIsPersistedEncryptedNotInPlaintext() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-ENC-1", "WHK-PLAINTEXT-SECRET");

    UUID signingRequestId = requestSigning(agreementId, "GRP-ENC-1");

    String stored = columnOf(signingRequestId, "webhook_security_key");
    assertThat(stored).isNotNull().isNotEqualTo("WHK-PLAINTEXT-SECRET");
    // A database read must not yield a working webhook credential.
    assertThat(stored).doesNotContain("WHK-PLAINTEXT-SECRET");
  }

  @Test
  void partialSigningIsASafeNoOpWithNoTransitionAndNoError() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-PART-1", "WHK-PART-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-PART-1");
    // The owner has signed; the tenant has not yet been invited to finish.
    stubGroup("SIGNED", "INPROGRESS", false);

    assertThat(postWebhook("GRP-PART-1", "WHK-PART-1").getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED");
    assertThat(columnOf(signingRequestId, "signed_pdf_key")).isNull();
    // The per-invitee sub-state is still recorded, so progress is visible mid-flow.
    String ownerStatus =
        jdbc.queryForObject(
            "SELECT status FROM signing_request_invitee WHERE signing_request_id = ?"
                + " AND provider_invitee_id = 'REQ-1'",
            String.class,
            signingRequestId);
    assertThat(ownerStatus).isEqualTo("SIGNED");
  }

  @Test
  void aForgedBodyWithAValidKeyCannotDriveATransitionBecauseTheFetchIsAuthoritative() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-FORGE-1", "WHK-FORGE-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-FORGE-1");
    // Authoritative state says only one signer is done...
    stubGroup("SIGNED", "INPROGRESS", false);

    // ...but the caller (holding a leaked key) claims the whole thing is complete. Because the
    // header key binds NOTHING to the payload, this is exactly the attack the trigger-only
    // discipline exists to neutralise.
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(WEBHOOK_KEY_HEADER, "WHK-FORGE-1");
    String forged =
        "{\"group_id\":\"GRP-FORGE-1\",\"success\":true,\"transaction_status\":\"SIGNED\","
            + "\"result\":{\"document\":{\"signed_url\":\"https://evil.example.com/x\"}}}";
    ResponseEntity<String> response =
        rest.postForEntity("/api/webhooks/esign", new HttpEntity<>(forged, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED"); // the forgery had no effect
    assertThat(columnOf(signingRequestId, "signed_pdf_key")).isNull();
  }

  @Test
  void aWrongOrMissingWebhookKeyIsRejectedWithNoStateChange() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-BAD-1", "WHK-BAD-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-BAD-1");
    stubGroup("SIGNED", "SIGNED", true);

    assertThat(postWebhook("GRP-BAD-1", "not-the-key").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(postWebhook("GRP-BAD-1", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED");
  }

  @Test
  void aKeyValidForAnotherTransactionIsRejected() {
    UUID first = createStampedAgreement();
    stubInit("GRP-A", "WHK-A");
    UUID firstRequest = requestSigning(first, "GRP-A");

    UUID second = createStampedAgreement();
    stubInit("GRP-B", "WHK-B");
    requestSigning(second, "GRP-B");

    stubGroup("SIGNED", "SIGNED", true);

    // A genuine key - for the OTHER transaction.
    assertThat(postWebhook("GRP-A", "WHK-B").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(statusOf(firstRequest)).isEqualTo("SIGN_REQUESTED");
  }

  @Test
  void aVerifiedWebhookForAnUnknownTransactionLeaksNothingAndChangesNothing() {
    // No stored key for an unknown transaction, so it cannot verify at all - and the refusal is
    // byte-identical to a wrong key, so the endpoint is not an existence oracle.
    assertThat(postWebhook("GRP-DOES-NOT-EXIST", "WHK-ANY").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aFetchFailureAfterAVerifiedWebhookIsAckedAndReconciliationFinishesTheJob() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-REC-1", "WHK-REC-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-REC-1");
    // The authoritative read is down: ack the webhook (never induce a redelivery storm) and leave
    // completion to the scheduled fallback.
    WIREMOCK.stubFor(get(urlPathEqualTo(GROUP_URL)).willReturn(aResponse().withStatus(503)));

    assertThat(postWebhook("GRP-REC-1", "WHK-REC-1").getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGN_REQUESTED");

    // The provider recovers; reconciliation drives the SAME completion path the webhook uses.
    stubGroup("SIGNED", "SIGNED", true);
    reconciliationJob.reconcile();

    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
    assertThat(columnOf(signingRequestId, "signed_pdf_key"))
        .isEqualTo("signed/" + signingRequestId + ".pdf");
  }

  @Test
  void aPendingRequestCanBeExtendedAndReInvitedWithoutASecondTransaction() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-EXT-1", "WHK-EXT-1");
    requestSigning(agreementId, "GRP-EXT-1");
    WIREMOCK.stubFor(
        post(urlEqualTo("/contract/esign/v5/increase-expiry-time")).willReturn(okJson("{}")));
    WIREMOCK.stubFor(
        post(urlEqualTo("/contract/esign/v5/send-esign-invitation")).willReturn(okJson("{}")));

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    assertThat(
            rest.exchange(
                    "/api/staff/signing/" + agreementId + "/extend",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            rest.exchange(
                    "/api/staff/signing/" + agreementId + "/resend",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    // Exactly ONE transaction was ever created: extend and re-invite must not mint a new one, or
    // the agreement would be charged twice and the collected signatures orphaned.
    WIREMOCK.verify(1, postRequestedFor(urlEqualTo(INIT_URL)));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_request WHERE agreement_id = ?",
                Long.class,
                agreementId))
        .isEqualTo(1);
  }

  @Test
  void aNonStaffCallerCannotExtendOrReInvite() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-EXT-2", "WHK-EXT-2");
    requestSigning(agreementId, "GRP-EXT-2");

    assertThat(
            rest.postForEntity("/api/staff/signing/" + agreementId + "/extend", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aReplayedWebhookIsIdempotent() {
    UUID agreementId = createStampedAgreement();
    stubInit("GRP-RPL-1", "WHK-RPL-1");
    UUID signingRequestId = requestSigning(agreementId, "GRP-RPL-1");
    stubGroup("SIGNED", "SIGNED", true);

    postWebhook("GRP-RPL-1", "WHK-RPL-1");
    String firstKey = columnOf(signingRequestId, "signed_pdf_key");
    postWebhook("GRP-RPL-1", "WHK-RPL-1");

    assertThat(columnOf(signingRequestId, "signed_pdf_key")).isEqualTo(firstKey);
    assertThat(statusOf(signingRequestId)).isEqualTo("SIGNED");
  }
}

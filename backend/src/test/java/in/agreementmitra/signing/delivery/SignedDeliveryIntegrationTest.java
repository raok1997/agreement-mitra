package in.agreementmitra.signing.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.signingrequest.SigningRequestService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.RecordingEmailSender;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Delivery + closure end to end against real Postgres + MinIO (Testcontainers), a stubbed eSign
 * provider (WireMock) and an in-memory email seam that can be told to fail.
 *
 * <p>The two failure modes this exists to prevent, and how each is provoked here:
 *
 * <ol>
 *   <li><b>Emailing the document twice.</b> The completion path is re-entered by the webhook, by
 *       the reconciliation path, and concurrently - all three are exercised, and each must leave
 *       the message count unchanged.
 *   <li><b>Emailing it to the wrong address.</b> Every send is asserted against the address the
 *       invitation was issued to and at which that party signed.
 * </ol>
 *
 * <p>Also pinned: a delivery failure never disturbs the signing record or the stored artifacts, and
 * closure happens only when every party has actually received it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SignedDeliveryIntegrationTest {

  private static final String AUTH_TOKEN = "it-auth-token";
  private static final String WEBHOOK_MAC_KEY = "it-mac-key-value";

  private static final String OWNER_EMAIL = "asha@example.com";
  private static final String TENANT_EMAIL = "tara@example.com";

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

  @Autowired private RecordingEmailSender mail;
  @Autowired private SigningRequestService signingRequestService;
  @Autowired private SignedDocumentDeliveryService deliveryService;
  @Autowired private in.agreementmitra.identity.IdentityService identityService;
  @Autowired private in.agreementmitra.identity.oauth.HandoffService handoffService;
  @Autowired private in.agreementmitra.identity.session.SessionService sessionService;

  private String staffToken;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    mail.reset();
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "delivery-staff-" + UUID.randomUUID());
  }

  // --- fixtures ---------------------------------------------------------------

  /** An agreement taken all the way to "ready to sign": drafted, finalised, paid for, stamped. */
  private UUID readyToSign() {
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
                        "firstName", "Asha",
                        "lastName", "Owner",
                        "fatherName", "Ravi Owner",
                        "currentAddress", "1 A St",
                        "email", OWNER_EMAIL,
                        "role", "OWNER"),
                    Map.of(
                        "firstName", "Tara",
                        "lastName", "Tenant",
                        "fatherName", "Hari Tenant",
                        "currentAddress", "3 C St",
                        "email", TENANT_EMAIL,
                        "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    uploadDraft(id);
    assertThat(
            rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    // The payment gate ships REQUIRED; this file is about delivery, not payment.
    Payments.waive(jdbc, id);
    uploadStamp(id);
    return id;
  }

  private void uploadDraft(UUID agreementId) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.singlePage()) {
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

  private void stubCreate(String documentId) {
    WIREMOCK.stubFor(
        post(urlEqualTo("/api/v3.0/sign/request"))
            .willReturn(
                okJson(
                    "{\"status\":\"SUCCESS\",\"data\":{\"documentId\":\""
                        + documentId
                        + "\",\"invitees\":[{\"inviteeId\":\"INV-1\",\"email\":\""
                        + OWNER_EMAIL
                        + "\",\"signUrl\":\"https://sign/1\",\"expiryDate\":\"2026-01-01\"},"
                        + "{\"inviteeId\":\"INV-2\",\"email\":\""
                        + TENANT_EMAIL
                        + "\",\"signUrl\":\"https://sign/2\",\"expiryDate\":\"2026-01-02\"}]}}")));
  }

  private void stubDetails(String ownerStatus, String tenantStatus, boolean withArtifacts) {
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
                        + ",\"invitees\":[{\"inviteeId\":\"INV-1\",\"status\":\""
                        + ownerStatus
                        + "\"},{\"inviteeId\":\"INV-2\",\"status\":\""
                        + tenantStatus
                        + "\"}]}}")));
    if (withArtifacts) {
      WIREMOCK.stubFor(
          get(urlPathEqualTo("/files/signed.pdf"))
              .willReturn(aResponse().withBody("PDFBYTES".getBytes(StandardCharsets.UTF_8))));
      WIREMOCK.stubFor(
          get(urlPathEqualTo("/files/audit.bin"))
              .willReturn(aResponse().withBody("AUDITTRAIL".getBytes(StandardCharsets.UTF_8))));
    }
  }

  private UUID requestSigning(UUID agreementId, String documentId) {
    stubCreate(documentId);
    assertThat(
            rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
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

  private String closureState(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT closure_state FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private String closureReason(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT closure_reason FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private String signingStatus(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE agreement_id = ?", String.class, agreementId);
  }

  private String deliveryStatusFor(UUID agreementId, String recipient) {
    return jdbc.queryForObject(
        "SELECT status FROM signed_document_delivery WHERE agreement_id = ? AND recipient_email = ?",
        String.class,
        agreementId,
        recipient);
  }

  // --- tests ------------------------------------------------------------------

  @Test
  void completionEmailsBothPartiesExactlyOnceAndClosesTheAgreement() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-1");
    stubDetails("SIGNED", "SIGNED", true);

    assertThat(postWebhook("DOC-DEL-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // Exactly one message per party, each at the address that party actually signed at.
    assertThat(mail.sent()).hasSize(2);
    assertThat(mail.sentTo(OWNER_EMAIL)).hasSize(1);
    assertThat(mail.sentTo(TENANT_EMAIL)).hasSize(1);

    // ...carrying the signed agreement, and NOT the audit trail.
    var message = mail.sentTo(OWNER_EMAIL).get(0);
    assertThat(message.hasAttachment()).isTrue();
    assertThat(new String(message.attachment().content(), StandardCharsets.UTF_8))
        .isEqualTo("PDFBYTES");
    assertThat(message.attachment().contentType()).isEqualTo("application/pdf");
    assertThat(mail.sent())
        .allSatisfy(
            m -> {
              assertThat(new String(m.attachment().content(), StandardCharsets.UTF_8))
                  .isNotEqualTo("AUDITTRAIL");
              assertThat(m.attachment().filename()).doesNotContain("audit");
              assertThat(m.body().toLowerCase(java.util.Locale.ROOT)).doesNotContain("audit");
            });

    // Signed and delivered to every party -> closed, as completed, with a recorded time.
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
    assertThat(closureReason(agreementId)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT closed_at FROM agreement WHERE id = ?",
                java.sql.Timestamp.class,
                agreementId))
        .isNotNull();
    // The signing FSM is untouched by closure: SIGNED stays terminal for signing.
    assertThat(signingStatus(agreementId)).isEqualTo("SIGNED");
  }

  @Test
  void aRedeliveredCompletionWebhookSendsNothingFurther() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-2");
    stubDetails("SIGNED", "SIGNED", true);

    postWebhook("DOC-DEL-2");
    assertThat(mail.sent()).hasSize(2);

    // The vendor re-delivers. The claim on each recipient record is what makes this a no-op.
    postWebhook("DOC-DEL-2");
    postWebhook("DOC-DEL-2");

    assertThat(mail.sent()).hasSize(2);
    assertThat(mail.sentTo(OWNER_EMAIL)).hasSize(1);
    assertThat(mail.sentTo(TENANT_EMAIL)).hasSize(1);
  }

  @Test
  void reconciliationReEnteringCompletionAfterDeliverySendsNothingFurther() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-3");
    stubDetails("SIGNED", "SIGNED", true);

    postWebhook("DOC-DEL-3");
    assertThat(mail.sent()).hasSize(2);

    // completeDocument IS the reconciliation job's entry point - the job selects rows and calls
    // exactly this, so driving it directly exercises the same re-entry without waiting on a
    // scheduler.
    signingRequestService.completeDocument("DOC-DEL-3");
    signingRequestService.completeDocument("DOC-DEL-3");

    assertThat(mail.sent()).hasSize(2);
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
  }

  @Test
  void theRetrySweepNeverManufacturesDeliveriesForAnAgreementThatHasNoDeliveryRecords() {
    // THE OTHER HALF OF THE DEPLOY HAZARD (signed-delivery-and-closure, Migration Plan step 3).
    // V18 ships no backfill, so on deploy every pre-existing SIGNED agreement has artifacts stored
    // and ZERO delivery rows. Deleting the rows reproduces exactly that state. The retry sweep
    // selects DUE ROWS, not agreements - and ensureRecords, which would create rows and send from
    // them, is only ever reached through a row the sweep already found. Widening the sweep to
    // select agreements instead would silently turn a deploy into a mass send.
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-BACKFILL");
    stubDetails("SIGNED", "SIGNED", true);
    postWebhook("DOC-DEL-BACKFILL");
    assertThat(mail.sent()).hasSize(2);

    jdbc.update("DELETE FROM signed_document_delivery WHERE agreement_id = ?", agreementId);
    jdbc.update(
        "UPDATE agreement SET closure_state = 'OPEN', closure_reason = NULL, closed_at = NULL"
            + " WHERE id = ?",
        agreementId);
    mail.reset();

    deliveryService.retryDue();

    assertThat(mail.sent()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signed_document_delivery WHERE agreement_id = ?",
                Integer.class,
                agreementId))
        .isZero();
    assertThat(closureState(agreementId)).isEqualTo("OPEN");
  }

  @Test
  void concurrentCompletionsStillSendEachRecipientExactlyOneMessage() throws Exception {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-4");
    stubDetails("SIGNED", "SIGNED", true);

    int threads = 6;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                signingRequestService.completeDocument("DOC-DEL-4");
              } catch (Exception ignored) {
                // A losing thread may fail; what matters is that nothing is sent twice.
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(mail.sentTo(OWNER_EMAIL)).hasSize(1);
    assertThat(mail.sentTo(TENANT_EMAIL)).hasSize(1);
    assertThat(mail.sent()).hasSize(2);
  }

  @Test
  void oneHardBounceLeavesTheOtherPartyDeliveredTheAgreementOpenAndTheSignatureIntact() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-5");
    stubDetails("SIGNED", "SIGNED", true);
    mail.failFor(
        TENANT_EMAIL,
        message -> EmailDeliveryException.permanentFailure("recipient-address-rejected", null));

    postWebhook("DOC-DEL-5");

    // The owner still gets their agreement: recipients fail independently.
    assertThat(mail.sentTo(OWNER_EMAIL)).hasSize(1);
    assertThat(mail.sentTo(TENANT_EMAIL)).isEmpty();
    assertThat(deliveryStatusFor(agreementId, OWNER_EMAIL)).isEqualTo("SENT");
    assertThat(deliveryStatusFor(agreementId, TENANT_EMAIL)).isEqualTo("FAILED");

    // Partial delivery does NOT close: there is outstanding work and somebody must see it.
    assertThat(closureState(agreementId)).isEqualTo("OPEN");
    // A bounced mailbox does not un-sign an agreement, and the artifacts are untouched.
    assertThat(signingStatus(agreementId)).isEqualTo("SIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT signed_pdf_key FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isNotNull();

    // ...and staff can see which party is stuck and why, with the address redacted.
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    @SuppressWarnings("unchecked")
    ResponseEntity<List> view =
        rest.exchange(
            "/api/staff/deliveries/" + agreementId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            List.class);
    assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = view.getBody();
    assertThat(rows).hasSize(2);
    Map<String, Object> failed =
        rows.stream().filter(r -> "FAILED".equals(r.get("status"))).findFirst().orElseThrow();
    assertThat(failed.get("needsAttention")).isEqualTo(true);
    assertThat(failed.get("lastError")).isEqualTo("recipient-address-rejected");
    // Redacted: enough to act on, not enough to read a party's mailbox off a support screen.
    assertThat((String) failed.get("recipient")).isEqualTo("t***@example.com");
    assertThat(rows.toString()).doesNotContain(TENANT_EMAIL);
  }

  @Test
  void aStaffResendDeliversAgainAndIsRecordedAsASeparateAttributedAttempt() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-6");
    stubDetails("SIGNED", "SIGNED", true);
    mail.failFor(
        TENANT_EMAIL,
        message -> EmailDeliveryException.permanentFailure("recipient-address-rejected", null));
    postWebhook("DOC-DEL-6");
    assertThat(deliveryStatusFor(agreementId, TENANT_EMAIL)).isEqualTo("FAILED");

    // The mailbox is fixed out of band; staff ask for a re-send.
    mail.healAll();
    UUID deliveryId =
        UUID.fromString(
            jdbc.queryForObject(
                "SELECT id::text FROM signed_document_delivery"
                    + " WHERE agreement_id = ? AND recipient_email = ?",
                String.class,
                agreementId,
                TENANT_EMAIL));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    ResponseEntity<String> resent =
        rest.exchange(
            "/api/staff/deliveries/" + deliveryId + "/resend",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class);

    assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(mail.sentTo(TENANT_EMAIL)).hasSize(1);
    assertThat(deliveryStatusFor(agreementId, TENANT_EMAIL)).isEqualTo("SENT");
    // Recorded as a DISTINCT, attributed attempt - never mistakable for an automatic retry.
    assertThat(
            jdbc.queryForObject(
                "SELECT resend_count FROM signed_document_delivery WHERE id = ?",
                Integer.class,
                deliveryId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT resent_by_identity_id FROM signed_document_delivery WHERE id = ?",
                UUID.class,
                deliveryId))
        .isNotNull();
    // Now everybody has it, so the agreement closes.
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
    assertThat(closureReason(agreementId)).isEqualTo("COMPLETED");
  }

  @Test
  void anUnavailableMailSeamStillStoresArtifactsRecordsCompletionAndLeavesDeliveryPending() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-7");
    stubDetails("SIGNED", "SIGNED", true);
    mail.failEverything(
        message -> EmailDeliveryException.transientFailure("provider-unavailable", null));

    assertThat(postWebhook("DOC-DEL-7").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // The legal record is intact and complete: delivery is downstream of it, never part of it.
    assertThat(signingStatus(agreementId)).isEqualTo("SIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT signed_pdf_key FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isNotNull();
    assertThat(closureState(agreementId)).isEqualTo("OPEN");
    // Both recipients are pending another attempt, each having counted one.
    assertThat(deliveryStatusFor(agreementId, OWNER_EMAIL)).isEqualTo("PENDING");
    assertThat(deliveryStatusFor(agreementId, TENANT_EMAIL)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT MIN(attempts) FROM signed_document_delivery WHERE agreement_id = ?",
                Integer.class,
                agreementId))
        .isEqualTo(1);

    // The provider comes back; the retry sweep delivers and the agreement closes.
    mail.healAll();
    deliveryService.retryDue();

    assertThat(mail.sentTo(OWNER_EMAIL)).hasSize(1);
    assertThat(mail.sentTo(TENANT_EMAIL)).hasSize(1);
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
  }

  @Test
  void aRejectedSigningClosesTheAgreementAsAbandonedAndEmailsNobody() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-8");
    stubDetails("SIGNED", "REJECTED", false);

    postWebhook("DOC-DEL-8");

    assertThat(signingStatus(agreementId)).isEqualTo("FAILED");
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
    // Distinguishable from a completed agreement in the record, not collapsed into "closed".
    assertThat(closureReason(agreementId)).isEqualTo("ABANDONED_SIGNING_FAILED");
    assertThat(mail.sent()).isEmpty();
  }

  @Test
  void anExpiredSigningClosesTheAgreementWithItsOwnAbandonmentReason() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-9");
    stubDetails("SIGNED", "EXPIRED", false);

    postWebhook("DOC-DEL-9");

    assertThat(signingStatus(agreementId)).isEqualTo("EXPIRED");
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");
    assertThat(closureReason(agreementId)).isEqualTo("ABANDONED_SIGNING_EXPIRED");
    assertThat(mail.sent()).isEmpty();
  }

  @Test
  void aClosedAgreementRefusesFurtherFulfilmentActionsAndLeavesTheStaffQueue() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-10");
    stubDetails("SIGNED", "SIGNED", true);
    postWebhook("DOC-DEL-10");
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");

    // Advancing a closed agreement is refused, with its own distinct 409 kind.
    ResponseEntity<String> signing =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);
    assertThat(signing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(signing.getBody()).contains("agreement-closed");
    ResponseEntity<String> finalise =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, String.class);
    assertThat(finalise.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(finalise.getBody()).contains("agreement-closed");
    // ...and it stays closed.
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");

    // A separate order still awaiting a stamp is on the queue; closing it takes it off.
    UUID awaiting = readyToSignWithoutStamp();
    assertThat(stampQueueIds()).contains(awaiting.toString());
    jdbc.update(
        "UPDATE agreement SET closure_state = 'CLOSED', closure_reason = 'ABANDONED_STAMP_FAILED',"
            + " closed_at = now() WHERE id = ?",
        awaiting);
    assertThat(stampQueueIds()).doesNotContain(awaiting.toString());
  }

  /** Drafted + finalised + paid for, but no stamp yet - so it rests on the staff queue. */
  private UUID readyToSignWithoutStamp() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "9 Residency Road, Bengaluru",
            "monthlyRent", "18000.00",
            "securityDeposit", "36000.00",
            "startDate", "2026-02-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    Map.of(
                        "firstName", "Queue",
                        "lastName", "Owner",
                        "fatherName", "Q Owner",
                        "currentAddress", "1 Q St",
                        "email", "queue-owner@example.com",
                        "role", "OWNER"),
                    Map.of(
                        "firstName", "Queue",
                        "lastName", "Tenant",
                        "fatherName", "Q Tenant",
                        "currentAddress", "2 Q St",
                        "email", "queue-tenant@example.com",
                        "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    uploadDraft(id);
    rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class);
    Payments.waive(jdbc, id);
    return id;
  }

  private String stampQueueIds() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    ResponseEntity<String> queue =
        rest.exchange(
            "/api/staff/estamp/queue", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
    return queue.getBody();
  }

  // --- the durable in-app copy ------------------------------------------------

  private String customerToken(String subject) {
    return StaffSessions.customerSession(identityService, handoffService, sessionService, subject);
  }

  private ResponseEntity<String> getAs(String path, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  @Test
  void aPartyRetrievesTheirSignedAgreementAndAnUnrelatedCallerIsRefusedWithoutDisclosure() {
    UUID agreementId = readyToSign();
    String ownerToken = customerToken("party-owner-" + UUID.randomUUID());
    HttpHeaders claimHeaders = new HttpHeaders();
    claimHeaders.setBearerAuth(ownerToken);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + agreementId + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(claimHeaders),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    requestSigning(agreementId, "DOC-DEL-11");
    stubDetails("SIGNED", "SIGNED", true);
    postWebhook("DOC-DEL-11");
    assertThat(closureState(agreementId)).isEqualTo("CLOSED");

    // The party gets the bytes, streamed through the application from the private bucket.
    ResponseEntity<byte[]> mine =
        rest.exchange(
            "/api/agreements/" + agreementId + "/signed-document",
            HttpMethod.GET,
            new HttpEntity<>(claimHeaders),
            byte[].class);
    assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(new String(mine.getBody(), StandardCharsets.UTF_8)).isEqualTo("PDFBYTES");
    assertThat(mine.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    // No public or long-lived URL is issued as the retrieval mechanism, and it is not cacheable.
    assertThat(mine.getHeaders().getCacheControl()).contains("no-store");
    assertThat(mine.getHeaders().getLocation()).isNull();

    // ...and this still works AFTER closure, which is the point of the previous assertion that the
    // agreement is closed: closure means "no work outstanding", not "no longer available".

    // An unrelated caller is refused IDENTICALLY for an agreement that exists and one that does
    // not, so the endpoint is not an existence oracle.
    String strangerToken = customerToken("party-stranger-" + UUID.randomUUID());
    ResponseEntity<String> refusedExisting =
        getAs("/api/agreements/" + agreementId + "/signed-document", strangerToken);
    ResponseEntity<String> refusedUnknown =
        getAs("/api/agreements/" + UUID.randomUUID() + "/signed-document", strangerToken);
    assertThat(refusedExisting.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(refusedUnknown.getStatusCode()).isEqualTo(refusedExisting.getStatusCode());
    assertThat(refusedUnknown.getBody()).isEqualTo(refusedExisting.getBody());

    // STAFF may retrieve it for support.
    ResponseEntity<String> staffCopy =
        getAs("/api/agreements/" + agreementId + "/signed-document", staffToken);
    assertThat(staffCopy.getStatusCode()).isEqualTo(HttpStatus.OK);

    // There is NO party-facing path to the audit trail at all: the fail-closed baseline denies it
    // rather than this surface opening it and then refusing.
    assertThat(getAs("/api/agreements/" + agreementId + "/audit-trail", ownerToken).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(getAs("/api/agreements/" + agreementId + "/audit", ownerToken).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theSignedDocumentIsNotRetrievableBeforeSigningCompletes() {
    UUID agreementId = readyToSign();
    requestSigning(agreementId, "DOC-DEL-12");

    // Same 404 as an unknown agreement: the endpoint reveals nothing about progress either.
    assertThat(getAs("/api/agreements/" + agreementId + "/signed-document", null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theDeliveryAndClosureMigrationIsApplied() {
    // The schema this capability adds really is migration-managed, and the context booted with
    // ddl-auto: validate against it.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '18' AND success = true",
                Integer.class))
        .isEqualTo(1);
  }
}

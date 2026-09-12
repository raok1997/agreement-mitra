package in.agreementmitra.signing.signingrequest;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
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
 * The payment gate in <b>REQUIRED</b> mode, end to end against real Postgres + MinIO and a stubbed
 * provider.
 *
 * <p>Production runs {@code OPTIONAL} and will for a while. Untested-in-production paths rot, so
 * {@code REQUIRED} is exercised here on every build - the whole reason for specifying it now is
 * that the expensive part of adding payment later is discovering where the pipeline should have
 * blocked, and this is where that answer gets pinned down.
 *
 * <p>Both gated steps are covered: <b>e-stamp intake</b> (where real money leaves - staff buy an
 * SHCIL certificate) and <b>eSign initiation</b> (where each signature is a billable vendor
 * transaction). In both cases the refusal must happen before any side effect: no blob written, no
 * provider called, no state transitioned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"payment.mode=REQUIRED", "esign.provider=leegality"})
class PaymentGateIntegrationTest {

  private static final String CREATE_URL = "/api/v3.0/sign/request";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("esign.leegality.base-url", () -> WIREMOCK.baseUrl() + "/api/");
    registry.add("esign.leegality.auth-token", () -> "it-auth-token");
    registry.add("esign.leegality.webhook-secret", () -> "it-mac-key");
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

  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private String staffToken;
  private String customerToken;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    WIREMOCK.stubFor(
        post(urlEqualTo(CREATE_URL))
            .willReturn(
                okJson(
                    "{\"status\":\"SUCCESS\",\"data\":{\"documentId\":\"DOC-PAY-"
                        + UUID.randomUUID()
                        + "\",\"invitees\":[{\"inviteeId\":\"INV-1\",\"signUrl\":\"https://sign/1\","
                        + "\"expiryDate\":\"2026-01-01\"},{\"inviteeId\":\"INV-2\","
                        + "\"signUrl\":\"https://sign/2\",\"expiryDate\":\"2026-01-02\"}]}}")));
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "pay-staff-" + UUID.randomUUID());
    customerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "pay-cust-" + UUID.randomUUID());
  }

  // --- fixtures --------------------------------------------------------------

  private UUID createFinalisedAgreement() {
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

    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.withEsignAnchors()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + id + "/draft",
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return id;
  }

  private ResponseEntity<String> uploadStamp(UUID agreementId) {
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
    return rest.exchange(
        "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
  }

  private ResponseEntity<String> confirmPayment(UUID agreementId, String reference) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(staffToken);
    String body =
        "{\"amount\":\"1499.00\",\"currency\":\"INR\",\"reference\":\"" + reference + "\"}";
    return rest.postForEntity(
        "/api/staff/payments/" + agreementId + "/confirm",
        new HttpEntity<>(body, headers),
        String.class);
  }

  private ResponseEntity<String> waivePayment(UUID agreementId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    return rest.exchange(
        "/api/staff/payments/" + agreementId + "/waive",
        HttpMethod.POST,
        new HttpEntity<>(null, headers),
        String.class);
  }

  private String paymentStateOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId);
  }

  // --- state -----------------------------------------------------------------

  @Test
  void aNewAgreementStartsUnpaid() {
    assertThat(paymentStateOf(createFinalisedAgreement())).isEqualTo("UNPAID");
  }

  @Test
  void aClientCannotSetPaymentStateOnCreate() {
    // The create body carries no payment field at all, and a smuggled one must be ignored rather
    // than honoured - payment state is server-managed, full stop.
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "9 Residency Road, Bengaluru",
            "monthlyRent", "10000.00",
            "securityDeposit", "20000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "paymentState", "PAID",
            "captureData", Map.of("paymentState", "PAID"),
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
    assertThat(paymentStateOf(UUID.fromString((String) created.getBody().get("id"))))
        .isEqualTo("UNPAID");
  }

  // --- the gate at e-stamp intake -------------------------------------------

  @Test
  void requiredModeBlocksStampIntakeForAnUnpaidAgreementBeforeAnySideEffect() {
    UUID agreementId = createFinalisedAgreement();

    ResponseEntity<String> response = uploadStamp(agreementId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // Distinguishable from a missing draft / an already-attached stamp / an unfinalised order.
    assertThat(response.getBody()).contains("payment-required");
    // Nothing was stamped and nothing transitioned: the request is exactly where it was.
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("PDF_GENERATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number FROM agreement WHERE id = ?",
                String.class,
                agreementId))
        .isNull();
    // The refusal is audited as its own outcome, not lumped in with "some error".
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM stamp_intake_audit WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("REJECTED_PAYMENT_REQUIRED");
  }

  @Test
  void requiredModeAdmitsStampIntakeOnceStaffRecordAPayment() {
    UUID agreementId = createFinalisedAgreement();

    assertThat(confirmPayment(agreementId, "PAY-" + UUID.randomUUID()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");

    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void requiredModeAdmitsStampIntakeOnPaymentWaiver() {
    UUID agreementId = createFinalisedAgreement();

    assertThat(waivePayment(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
    // A waiver is recorded as its OWN state and carries no invented amount or reference, so the
    // books can still answer "how much money came in".
    assertThat(paymentStateOf(agreementId)).isEqualTo("WAIVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_amount FROM agreement WHERE id = ?", String.class, agreementId))
        .isNull();

    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // --- the gate at eSign initiation -----------------------------------------

  @Test
  void requiredModeBlocksEsignInitiationForAnUnpaidAgreementWithNoProviderCall() {
    UUID agreementId = createFinalisedAgreement();
    // Get the agreement stamped legitimately (the gate would otherwise stop us at intake), then
    // return it to UNPAID directly in the database so the SECOND gate is what is under test.
    // Payment
    // state is server-managed, so a test is the only place this reversal can come from.
    assertThat(waivePayment(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
    jdbc.update(
        "UPDATE agreement SET payment_state = 'UNPAID', payment_recorded_at = NULL WHERE id = ?",
        agreementId);

    ResponseEntity<String> response =
        rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).contains("payment-required");
    // No vendor charge can have been incurred, because the vendor was never called.
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo(CREATE_URL)));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("STAMPED");
  }

  @Test
  void requiredModeAdmitsEsignInitiationForAPaidAgreement() {
    UUID agreementId = createFinalisedAgreement();
    assertThat(confirmPayment(agreementId, "PAY-" + UUID.randomUUID()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(
            rest.postForEntity("/api/signing/" + agreementId + "/request", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    WIREMOCK.verify(1, postRequestedFor(urlEqualTo(CREATE_URL)));
  }

  // --- the confirmation seam -------------------------------------------------

  @Test
  void anExternalPaymentReferenceCannotBeRecordedAgainstTwoAgreements() {
    String sharedReference = "PAY-" + UUID.randomUUID();
    assertThat(confirmPayment(createFinalisedAgreement(), sharedReference).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> second = confirmPayment(createFinalisedAgreement(), sharedReference);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(second.getBody()).contains("payment-reference-already-used");
  }

  @Test
  void aNonStaffCallerCannotRecordPaymentOrWaiveIt() {
    UUID agreementId = createFinalisedAgreement();

    HttpHeaders customer = new HttpHeaders();
    customer.setContentType(MediaType.APPLICATION_JSON);
    customer.setBearerAuth(customerToken);
    ResponseEntity<String> confirmed =
        rest.postForEntity(
            "/api/staff/payments/" + agreementId + "/confirm",
            new HttpEntity<>("{\"amount\":\"1.00\"}", customer),
            String.class);
    ResponseEntity<String> waived =
        rest.exchange(
            "/api/staff/payments/" + agreementId + "/waive",
            HttpMethod.POST,
            new HttpEntity<>(null, customer),
            String.class);
    ResponseEntity<String> anonymous =
        rest.postForEntity("/api/staff/payments/" + agreementId + "/confirm", null, String.class);

    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(waived.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
  }

  @Test
  void theActiveGateModeIsObservableAtRuntime() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/staff/payments/gate", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("REQUIRED");
  }

  @Test
  void theStaffQueueShowsWhoHasNotPaid() {
    UUID unpaid = createFinalisedAgreement();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/staff/estamp/queue", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains(unpaid.toString());
    assertThat(response.getBody()).contains("UNPAID");
  }
}

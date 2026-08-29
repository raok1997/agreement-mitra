package in.agreementmitra.signing.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 * The payment gate in <b>REQUIRED</b> mode, unblocked by a <b>gateway</b> payment.
 *
 * <p>Production runs {@code OPTIONAL} and will until a real payment has been observed settling in
 * live mode. That is exactly why this runs on every build: the expensive part of enabling payment
 * later is discovering that the gateway path never actually satisfied the gate, and this is where
 * that gets pinned down.
 *
 * <p>The manual STAFF path is covered by {@code PaymentGateIntegrationTest}; both producers feed
 * the same confirmation seam, and both must satisfy the same gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"payment.mode=REQUIRED", "esign.provider=leegality"})
class RazorpayPaymentGateIntegrationTest {

  private static final String ORDERS_URL = "/v1/orders";
  private static final String WEBHOOK_KEY = "gate-wh-mac";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("payment.razorpay.base-url", () -> WIREMOCK.baseUrl() + "/");
    registry.add("payment.razorpay.key-id", () -> "rzp_test_gate");
    registry.add("payment.razorpay.key-secret", () -> "gate-rzp-key");
    registry.add("payment.razorpay.webhook-secret", () -> WEBHOOK_KEY);
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
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
            "gate-staff-" + UUID.randomUUID());
  }

  private UUID createFinalisedAgreement() {
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

  private void payThroughTheGateway(UUID agreementId, String orderId, String paymentId) {
    WIREMOCK.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .willReturn(
                okJson(
                    "{\"id\":\""
                        + orderId
                        + "\",\"status\":\"created\",\"amount\":49900,\"currency\":\"INR\"}")));
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + agreementId + "/payment/order", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    String webhookBody =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""
            + paymentId
            + "\",\"order_id\":\""
            + orderId
            + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Razorpay-Signature", RazorpaySignatures.hmacSha256Hex(webhookBody, WEBHOOK_KEY));
    assertThat(
            rest.postForEntity(
                    "/api/webhooks/razorpay", new HttpEntity<>(webhookBody, headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void aGatewayPaymentSatisfiesTheGateThatAnUnpaidAgreementFails() {
    UUID agreementId = createFinalisedAgreement();

    // Before paying: the gated step is refused, distinguishably, with nothing stamped.
    ResponseEntity<String> refused = uploadStamp(agreementId);
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains("payment-required");
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number FROM agreement WHERE id = ?",
                String.class,
                agreementId))
        .isNull();

    // Pay through the gateway - order placed server-side, confirmed by a verified webhook.
    payThroughTheGateway(agreementId, "order_GATE1", "pay_GATE1");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("PAID");

    // The same gated step now proceeds. If this ever fails, the gateway is not actually feeding the
    // gate, and flipping payment.mode to REQUIRED in production would strand every customer.
    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}

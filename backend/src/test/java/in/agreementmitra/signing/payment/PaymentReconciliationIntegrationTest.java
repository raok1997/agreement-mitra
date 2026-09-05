package in.agreementmitra.signing.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.support.HarnessTestConfig;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The reconciliation fallback.
 *
 * <p>The worst outcome in this whole change is money taken and service withheld. A webhook can be
 * missed - the endpoint is down, the tunnel is not up in local dev, the delivery is dropped - so
 * this scan re-reads outstanding orders and confirms the ones the provider says were paid.
 *
 * <p>Equally important is the other half: it must not <b>invent</b> a payment. An order the
 * provider reports unpaid, failed, or expired is left alone, and running the job repeatedly changes
 * nothing beyond the first successful confirmation.
 *
 * <p>The scheduler is enabled here so the job bean exists, with a long initial delay so the only
 * runs are the ones this test asks for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "payment.reconciliation.enabled=true",
      "payment.reconciliation.initial-delay=PT30M",
      "payment.reconciliation.interval=PT30M",
      "payment.reconciliation.age-threshold=PT0S",
      "payment.order.ttl=PT30M"
    })
class PaymentReconciliationIntegrationTest {

  private static final String ORDERS_URL = "/v1/orders";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("payment.razorpay.base-url", () -> WIREMOCK.baseUrl() + "/");
    registry.add("payment.razorpay.key-id", () -> "rzp_test_recon");
    registry.add("payment.razorpay.key-secret", () -> "recon-key");
    registry.add("payment.razorpay.webhook-secret", () -> "recon-wh");
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PaymentOrderRepository orders;
  @Autowired private PaymentReconciliationJob job;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
  }

  // --- fixtures --------------------------------------------------------------

  private UUID createAgreement() {
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
    return UUID.fromString((String) created.getBody().get("id"));
  }

  /**
   * An order recorded directly, as if checkout had been started some time ago and the webhook never
   * arrived. Backdated so the reconciliation age threshold is unambiguously met.
   */
  private PaymentOrder outstandingOrder(UUID agreementId, String providerOrderId) {
    PaymentOrder order =
        PaymentOrder.create(
            agreementId,
            providerOrderId,
            agreementId.toString(),
            new Money(49_900L, "INR"),
            Instant.now().minus(Duration.ofHours(1)));
    return orders.save(order);
  }

  private void stubOrder(String orderId, String status) {
    WIREMOCK.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/" + orderId))
            .willReturn(
                okJson(
                    "{\"id\":\""
                        + orderId
                        + "\",\"status\":\""
                        + status
                        + "\",\"amount\":49900,\"currency\":\"INR\"}")));
  }

  private void stubCapturedPayment(String orderId, String paymentId, long amountMinorUnits) {
    WIREMOCK.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/" + orderId + "/payments"))
            .willReturn(
                okJson(
                    "{\"count\":1,\"items\":[{\"id\":\""
                        + paymentId
                        + "\",\"status\":\"captured\",\"amount\":"
                        + amountMinorUnits
                        + ",\"currency\":\"INR\"}]}")));
  }

  private String paymentStateOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private String orderStatusOf(String providerOrderId) {
    return jdbc.queryForObject(
        "SELECT status FROM payment_order WHERE provider_order_id = ?",
        String.class,
        providerOrderId);
  }

  // --- recovery --------------------------------------------------------------

  @Test
  void aPaymentWhoseWebhookNeverArrivedIsRecovered() {
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_RECOVER");
    stubOrder("order_RECOVER", "paid");
    stubCapturedPayment("order_RECOVER", "pay_RECOVER", 49_900L);
    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");

    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(orderStatusOf("order_RECOVER")).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_reference FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("pay_RECOVER");
  }

  @Test
  void reconciliationIsSafeToRunRepeatedly() {
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_REPEAT");
    stubOrder("order_REPEAT", "paid");
    stubCapturedPayment("order_REPEAT", "pay_REPEAT", 49_900L);

    job.reconcile();
    java.sql.Timestamp firstRecordedAt =
        jdbc.queryForObject(
            "SELECT payment_recorded_at FROM agreement WHERE id = ?",
            java.sql.Timestamp.class,
            agreementId);
    job.reconcile();
    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_recorded_at FROM agreement WHERE id = ?",
                java.sql.Timestamp.class,
                agreementId))
        .isEqualTo(firstRecordedAt);
  }

  // --- it cannot invent a payment -------------------------------------------

  @Test
  void anOrderTheProviderReportsUnpaidIsNeverConfirmed() {
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_UNPAID");
    stubOrder("order_UNPAID", "attempted");
    // A captured payment IS available at the provider - but the order does not say paid, so the
    // job must not go looking for one. Confirming on a half-read is how a payment gets invented.
    stubCapturedPayment("order_UNPAID", "pay_NOPE", 49_900L);

    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
  }

  @Test
  void anOrderWithNoCapturedPaymentIsNeverConfirmed() {
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_NOCAPTURE");
    stubOrder("order_NOCAPTURE", "paid");
    WIREMOCK.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_NOCAPTURE/payments"))
            .willReturn(okJson("{\"count\":0,\"items\":[]}")));

    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
  }

  @Test
  void anUnreachableProviderConfirmsNothingAndExpiresTheOrderOnceItIsPastItsLife() {
    // No stub at all: every read fails. That is not evidence the customer did not pay, so nothing
    // is confirmed; the order is simply retired so a fresh one can be started.
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_UNREACHABLE");

    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    assertThat(orderStatusOf("order_UNREACHABLE")).isEqualTo("EXPIRED");
  }

  @Test
  void anAmountMismatchAtReconciliationIsNotRecordedAsPayment() {
    UUID agreementId = createAgreement();
    outstandingOrder(agreementId, "order_RECONMM");
    stubOrder("order_RECONMM", "paid");
    stubCapturedPayment("order_RECONMM", "pay_RECONMM", 1L); // wrong amount

    job.reconcile();

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    assertThat(orderStatusOf("order_RECONMM")).isEqualTo("CREATED");
  }
}

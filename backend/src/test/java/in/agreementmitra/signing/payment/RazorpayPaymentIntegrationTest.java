package in.agreementmitra.signing.payment;

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
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.RecordingEmailSender;
import in.agreementmitra.support.StaffSessions;
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
 * Gateway payment end to end against real Postgres + MinIO and a stubbed Razorpay.
 *
 * <p>The case this file exists for is the ordinary one: <b>a customer pays and closes the tab.</b>
 * If the browser callback were authoritative they would be charged and stay {@code UNPAID}. So the
 * webhook is what settles payment, the callback settles nothing, and both facts are asserted here
 * rather than assumed.
 *
 * <p>Everything runs against fabricated credentials and a WireMock stub. No live account exists for
 * this repository, no real money is involved, and the payment gate stays {@code OPTIONAL}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RazorpayPaymentIntegrationTest {

  private static final String ORDERS_URL = "/v1/orders";
  private static final String KEY_ID = "rzp_test_it";

  /**
   * Fabricated, and deliberately different from each other - they must never be interchangeable.
   */
  private static final String API_KEY = "it-rzp-key";

  private static final String WEBHOOK_KEY = "it-wh-mac";

  private static final String RECOVERY_BASE_URL = "https://app.example.test";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("payment.razorpay.base-url", () -> WIREMOCK.baseUrl() + "/");
    registry.add("payment.razorpay.key-id", () -> KEY_ID);
    registry.add("payment.razorpay.key-secret", () -> API_KEY);
    registry.add("payment.razorpay.webhook-secret", () -> WEBHOOK_KEY);
    // The recovery link rides on payment confirmation, so these tests need a base URL
    // and an enabled channel to observe it at all.
    registry.add("delivery.public-base-url", () -> RECOVERY_BASE_URL);
    registry.add("delivery.channels.email.enabled", () -> "true");
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
  @Autowired private RecordingEmailSender mail;

  private String customerToken;
  private String otherCustomerToken;
  private String staffToken;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    mail.reset();
    customerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "rzp-cust-" + UUID.randomUUID());
    otherCustomerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "rzp-other-" + UUID.randomUUID());
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "rzp-staff-" + UUID.randomUUID());
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

  private void claimFor(UUID agreementId, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + agreementId + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private void stubOrderCreation(String orderId, long amountMinorUnits) {
    WIREMOCK.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .willReturn(
                okJson(
                    "{\"id\":\""
                        + orderId
                        + "\",\"status\":\"created\",\"amount\":"
                        + amountMinorUnits
                        + ",\"currency\":\"INR\"}")));
  }

  private void stubOrderRead(String orderId, String status, long amountMinorUnits) {
    WIREMOCK.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/" + orderId))
            .willReturn(
                okJson(
                    "{\"id\":\""
                        + orderId
                        + "\",\"status\":\""
                        + status
                        + "\",\"amount\":"
                        + amountMinorUnits
                        + ",\"currency\":\"INR\"}")));
  }

  private ResponseEntity<String> startCheckout(UUID agreementId, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(
        "/api/agreements/" + agreementId + "/payment/order",
        HttpMethod.POST,
        new HttpEntity<>(headers),
        String.class);
  }

  private ResponseEntity<String> readProgress(UUID agreementId, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(
        "/api/agreements/" + agreementId + "/payment",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }

  private static String paymentCapturedBody(String orderId, String paymentId, long amount) {
    return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
        + "\"id\":\""
        + paymentId
        + "\",\"order_id\":\""
        + orderId
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
  }

  private static String orderPaidBody(String orderId, String paymentId, long amount) {
    return "{\"event\":\"order.paid\",\"payload\":{\"order\":{\"entity\":{\"id\":\""
        + orderId
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\"INR\",\"status\":\"paid\"}},\"payment\":{\"entity\":{\"id\":\""
        + paymentId
        + "\",\"order_id\":\""
        + orderId
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
  }

  private ResponseEntity<String> deliverWebhook(String body, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (signature != null) {
      headers.set("X-Razorpay-Signature", signature);
    }
    return rest.postForEntity(
        "/api/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> deliverSignedWebhook(String body) {
    return deliverWebhook(body, RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_KEY));
  }

  private String paymentStateOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private String orderStatusOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT status FROM payment_order WHERE agreement_id = ?", String.class, agreementId);
  }

  // --- the happy path --------------------------------------------------------

  @Test
  void startingCheckoutPersistsTheJoinAndReturnsOnlyPublicValues() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_HAPPY1", 49_900L);

    ResponseEntity<String> response = startCheckout(agreementId, customerToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains(KEY_ID).contains("order_HAPPY1").contains("49900");
    // The two secrets must appear nowhere in anything the browser can see.
    assertThat(response.getBody()).doesNotContain(API_KEY).doesNotContain(WEBHOOK_KEY);

    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM payment_order WHERE agreement_id = ?", agreementId);
    assertThat(row.get("provider_order_id")).isEqualTo("order_HAPPY1");
    assertThat(row.get("receipt")).isEqualTo(agreementId.toString());
    assertThat(((Number) row.get("amount_minor_units")).longValue()).isEqualTo(49_900L);
    assertThat(row.get("currency")).isEqualTo("INR");
    assertThat(row.get("status")).isEqualTo("CREATED");
  }

  @Test
  void aVerifiedWebhookMarksTheAgreementPaidThroughTheGateSeam() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_HAPPY2", 49_900L);
    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> ack =
        deliverSignedWebhook(paymentCapturedBody("order_HAPPY2", "pay_HAPPY2", 49_900L));

    assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(orderStatusOf(agreementId)).isEqualTo("PAID");
    // Recorded through the vendor-neutral seam: the payment id IS the external reference, and the
    // amount lands as exact major units, not a rounded float.
    Map<String, Object> agreement =
        jdbc.queryForMap(
            "SELECT payment_reference, payment_amount, payment_currency, payment_recorded_at"
                + " FROM agreement WHERE id = ?",
            agreementId);
    assertThat(agreement.get("payment_reference")).isEqualTo("pay_HAPPY2");
    assertThat(agreement.get("payment_amount").toString()).isEqualTo("499.00");
    assertThat(agreement.get("payment_currency")).isEqualTo("INR");
    assertThat(agreement.get("payment_recorded_at")).isNotNull();
  }

  @Test
  void aCustomerWhoClosesTheTabIsStillMarkedPaid() {
    // THE case this whole design turns on. No callback is ever posted - the browser is gone - and
    // the customer must still end up PAID, because the webhook is what settles payment.
    UUID agreementId = createAgreement();
    stubOrderCreation("order_CLOSED", 49_900L);
    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

    deliverSignedWebhook(paymentCapturedBody("order_CLOSED", "pay_CLOSED", 49_900L));

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(readProgress(agreementId, customerToken).getBody()).contains("PAID");
  }

  // --- the browser callback is NOT authoritative -----------------------------

  @Test
  void aValidHandlerSignatureAloneDoesNotMarkTheAgreementPaid() {
    // The signature verifies - the provider really did issue it - and the agreement still must not
    // become PAID, because the provider's own record does not say the order was paid. A value that
    // travelled through the user's browser is a UX signal, never a confirmation.
    UUID agreementId = createAgreement();
    stubOrderCreation("order_UX1", 49_900L);
    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    stubOrderRead("order_UX1", "attempted", 49_900L); // provider: not paid

    String signature = RazorpaySignatures.hmacSha256Hex("order_UX1|pay_UX1", API_KEY);
    ResponseEntity<String> response = postCallback(agreementId, "order_UX1", "pay_UX1", signature);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    assertThat(response.getBody()).contains("UNPAID");
  }

  @Test
  void aForgedHandlerSignatureDoesNotConfirm() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_UX2", 49_900L);
    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    stubOrderRead("order_UX2", "paid", 49_900L); // even if the provider WOULD say paid

    ResponseEntity<String> response =
        postCallback(agreementId, "order_UX2", "pay_UX2", "forged-signature");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
  }

  @Test
  void aVerifiedCallbackMayTriggerAnAuthoritativeReadWhichIsWhatConfirms() {
    // The callback earns the right to ASK. The provider's answer is what decides - and the
    // confirmation goes through exactly the same path the webhook uses.
    UUID agreementId = createAgreement();
    stubOrderCreation("order_UX3", 49_900L);
    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    stubOrderRead("order_UX3", "paid", 49_900L);
    WIREMOCK.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_UX3/payments"))
            .willReturn(
                okJson(
                    "{\"count\":1,\"items\":[{\"id\":\"pay_UX3\",\"status\":\"captured\","
                        + "\"amount\":49900,\"currency\":\"INR\"}]}")));

    String signature = RazorpaySignatures.hmacSha256Hex("order_UX3|pay_UX3", API_KEY);
    ResponseEntity<String> response = postCallback(agreementId, "order_UX3", "pay_UX3", signature);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
  }

  private ResponseEntity<String> postCallback(
      UUID agreementId, String orderId, String paymentId, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(customerToken);
    String body =
        "{\"razorpayOrderId\":\""
            + orderId
            + "\",\"razorpayPaymentId\":\""
            + paymentId
            + "\",\"razorpaySignature\":\""
            + signature
            + "\"}";
    return rest.postForEntity(
        "/api/agreements/" + agreementId + "/payment/callback",
        new HttpEntity<>(body, headers),
        String.class);
  }

  // --- idempotency -----------------------------------------------------------

  // --- the recovery link that rides on payment confirmation (post-payment-continuity) ----------

  /**
   * 9.18 + 9.21b. The whole point of the unprompted send: a customer pays and closes the tab. The
   * webhook -- not the browser -- settles the payment, and the link must reach <b>every</b> party,
   * not only whoever happened to be at the keyboard.
   *
   * <p>The agreement is deliberately left <b>unclaimed</b>. An anonymous customer can pay (the
   * checkout routes are {@code permitAll}), and recovery only applies while nobody owns the
   * agreement -- so this unowned-and-paid state is precisely the one the feature exists for.
   */
  @Test
  void payingSendsEveryPartyALinkTheyCanOpen() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_RECOV1", 49_900L);
    startCheckout(agreementId, null);

    assertThat(
            deliverSignedWebhook(paymentCapturedBody("order_RECOV1", "pay_RECOV1", 49_900L))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(mail.sentTo("asha@example.com")).hasSize(1);
    assertThat(mail.sentTo("tara@example.com")).hasSize(1);

    // The link is the agreement identifier out of band, and it opens the agreement.
    String body = mail.sentTo("tara@example.com").get(0).body();
    assertThat(body).contains(RECOVERY_BASE_URL).contains(agreementId.toString());
    assertThat(rest.getForEntity("/api/agreements/" + agreementId, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  /**
   * 9.9. Task 5.2 claims the send is idempotent; nothing asserted it. The guarantee comes from
   * {@code PaymentConfirmations.apply} taking the order row {@code FOR UPDATE} and returning {@code
   * ALREADY_CONFIRMED} <b>before</b> it publishes -- so a refactor that moved the publish above
   * that guard would mail the customer once per provider retry, and Razorpay retries.
   */
  @Test
  void aRedeliveredWebhookDoesNotMailTheCustomerTwice() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_RECOV2", 49_900L);
    startCheckout(agreementId, null);
    String body = paymentCapturedBody("order_RECOV2", "pay_RECOV2", 49_900L);

    deliverSignedWebhook(body);
    deliverSignedWebhook(body);
    deliverSignedWebhook(body);

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(mail.sentTo("asha@example.com")).hasSize(1);
    assertThat(mail.sentTo("tara@example.com")).hasSize(1);
  }

  /**
   * 9.26. The customer's money has already moved by the time the link is sent, so a mail failure
   * must never reach back into the payment path. {@code RecoveryOnPaymentListener} swallows and
   * runs after commit; this asserts the outcome rather than trusting the construction.
   */
  @Test
  void aDispatchFailureDoesNotUnsettleTheConfirmedPayment() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_RECOV3", 49_900L);
    startCheckout(agreementId, null);
    mail.failEverything(m -> new IllegalStateException("provider refused"));

    ResponseEntity<String> delivered =
        deliverSignedWebhook(paymentCapturedBody("order_RECOV3", "pay_RECOV3", 49_900L));

    // Acknowledged, settled, and still reachable -- the failure is absorbed, not propagated.
    assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(rest.getForEntity("/api/agreements/" + agreementId, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    mail.healAll();
  }

  @Test
  void aRedeliveredWebhookRecordsNothingExtraAndChangesNothing() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_IDEM1", 49_900L);
    startCheckout(agreementId, customerToken);
    String body = paymentCapturedBody("order_IDEM1", "pay_IDEM1", 49_900L);

    assertThat(deliverSignedWebhook(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    Instant firstRecordedAt =
        jdbc.queryForObject(
                "SELECT payment_recorded_at FROM agreement WHERE id = ?",
                java.sql.Timestamp.class,
                agreementId)
            .toInstant();

    // Delivered again, twice, exactly as the provider would retry.
    assertThat(deliverSignedWebhook(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(deliverSignedWebhook(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE agreement_id = ?",
                Integer.class,
                agreementId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                    "SELECT payment_recorded_at FROM agreement WHERE id = ?",
                    java.sql.Timestamp.class,
                    agreementId)
                .toInstant())
        .isEqualTo(firstRecordedAt);
  }

  @Test
  void twoDifferentEventsForOnePaymentConfirmExactlyOnce() {
    // Razorpay sends payment.captured AND order.paid for the same successful payment. Both describe
    // one payment, and one payment must be recorded.
    UUID agreementId = createAgreement();
    stubOrderCreation("order_IDEM2", 49_900L);
    startCheckout(agreementId, customerToken);

    assertThat(
            deliverSignedWebhook(paymentCapturedBody("order_IDEM2", "pay_IDEM2", 49_900L))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            deliverSignedWebhook(orderPaidBody("order_IDEM2", "pay_IDEM2", 49_900L))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_reference FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("pay_IDEM2");
  }

  @Test
  void reloadingDuringCheckoutReusesTheOutstandingOrder() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_REUSE", 49_900L);

    ResponseEntity<String> first = startCheckout(agreementId, customerToken);
    ResponseEntity<String> second = startCheckout(agreementId, customerToken);
    ResponseEntity<String> third = startCheckout(agreementId, customerToken);

    assertThat(first.getBody()).contains("order_REUSE");
    assertThat(second.getBody()).contains("order_REUSE");
    assertThat(third.getBody()).contains("order_REUSE");
    // One provider order, not three. Without this, an agreement accumulates orders every time a
    // customer refreshes and reconciliation has to guess which one counts.
    WIREMOCK.verify(1, postRequestedFor(urlEqualTo(ORDERS_URL)));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE agreement_id = ?",
                Integer.class,
                agreementId))
        .isEqualTo(1);
  }

  @Test
  void oneProviderOrderCannotBeRecordedAgainstTwoAgreements() {
    UUID first = createAgreement();
    stubOrderCreation("order_SHARED", 49_900L);
    assertThat(startCheckout(first, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID second = createAgreement();
    ResponseEntity<String> clash = startCheckout(second, customerToken);

    // The unique index on the provider order id is the backstop: a provider that returned the same
    // order id for a second agreement must not credit one payment twice.
    assertThat(clash.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE provider_order_id = ?",
                Integer.class,
                "order_SHARED"))
        .isEqualTo(1);
  }

  // --- the amount cross-check ------------------------------------------------

  @Test
  void anAmountMismatchIsNotTreatedAsPayment() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_MISMATCH", 49_900L);
    startCheckout(agreementId, customerToken);

    // Verified webhook, correct order, wrong amount. Recording it would credit the agreement for a
    // sum it was never invoiced.
    assertThat(
            deliverSignedWebhook(paymentCapturedBody("order_MISMATCH", "pay_MM", 1L))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    assertThat(orderStatusOf(agreementId)).isEqualTo("CREATED");
  }

  @Test
  void aCurrencyMismatchIsNotTreatedAsPayment() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_CURR", 49_900L);
    startCheckout(agreementId, customerToken);
    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_C\","
            + "\"order_id\":\"order_CURR\",\"amount\":49900,\"currency\":\"USD\"}}}}";

    assertThat(deliverSignedWebhook(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
  }

  // --- webhook posture -------------------------------------------------------

  @Test
  void anUnverifiedWebhookIsRejectedWithNoStateChange() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_FORGE", 49_900L);
    startCheckout(agreementId, customerToken);
    String body = paymentCapturedBody("order_FORGE", "pay_FORGE", 49_900L);

    assertThat(deliverWebhook(body, "forged").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(deliverWebhook(body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    // Signed with the API key rather than the webhook key - the classic crossed-wires bug.
    assertThat(
            deliverWebhook(body, RazorpaySignatures.hmacSha256Hex(body, API_KEY)).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    assertThat(orderStatusOf(agreementId)).isEqualTo("CREATED");
  }

  @Test
  void aVerifiedWebhookForAnUnknownOrderIsAcknowledgedIndistinguishably() {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_KNOWN", 49_900L);
    startCheckout(agreementId, customerToken);

    ResponseEntity<String> known =
        deliverSignedWebhook(paymentCapturedBody("order_KNOWN", "pay_K", 49_900L));
    ResponseEntity<String> unknown =
        deliverSignedWebhook(paymentCapturedBody("order_NOTOURS", "pay_U", 49_900L));

    assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode());
    assertThat(unknown.getBody()).isEqualTo(known.getBody());
  }

  // --- authorization ---------------------------------------------------------

  @Test
  void aNonOwnerCannotStartPaymentOrReadItsProgress() {
    UUID agreementId = createAgreement();
    claimFor(agreementId, customerToken);
    stubOrderCreation("order_AUTHZ", 49_900L);

    assertThat(startCheckout(agreementId, otherCustomerToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(readProgress(agreementId, otherCustomerToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    // Same 404 as an unknown agreement, so ownership cannot be probed - and no order was placed.
    assertThat(startCheckout(UUID.randomUUID(), otherCustomerToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo(ORDERS_URL)));
  }

  @Test
  void theOwnerAndStaffCanBothActOnAClaimedAgreement() {
    UUID agreementId = createAgreement();
    claimFor(agreementId, customerToken);
    stubOrderCreation("order_OWNER", 49_900L);

    assertThat(startCheckout(agreementId, customerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readProgress(agreementId, staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void progressReportsUnpaidBeforeAnyOrderExists() {
    UUID agreementId = createAgreement();

    ResponseEntity<String> response = readProgress(agreementId, customerToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("UNPAID");
  }

  // --- the gate ships REQUIRED ----------------------------------------------

  @Test
  void theShippedGateModeIsRequiredAndObservableAtRuntime() {
    // The gate ships REQUIRED: payment must clear before staff spend real money on an e-stamp. An
    // operator has to be able to see that at runtime without reading configuration files, because
    // "why did this order stop?" is the first question a blocked queue produces.
    UUID agreementId = createAgreement();

    assertThat(paymentStateOf(agreementId)).isEqualTo("UNPAID");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);
    ResponseEntity<String> gate =
        rest.exchange(
            "/api/staff/payments/gate", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(gate.getBody()).contains("REQUIRED");
  }

  @Test
  void theManualStaffConfirmationPathStillWorksAlongsideTheGateway() {
    // An out-of-band payment still has to be recordable. The gateway is a SECOND producer of a
    // payment confirmation behind the same seam, not a replacement for the manual one.
    UUID agreementId = createAgreement();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(staffToken);

    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/staff/payments/" + agreementId + "/confirm",
            new HttpEntity<>(
                "{\"amount\":\"499.00\",\"currency\":\"INR\",\"reference\":\"NEFT-"
                    + UUID.randomUUID()
                    + "\"}",
                headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paymentStateOf(agreementId)).isEqualTo("PAID");
  }
}

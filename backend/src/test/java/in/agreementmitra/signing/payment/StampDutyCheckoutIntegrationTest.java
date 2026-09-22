package in.agreementmitra.signing.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.StaffSessions;
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
 * The stamp quote and checkout against real Postgres and the shipped Telangana rules, with a
 * stubbed Razorpay (state-stamp-duty-quoting: stamp-selection and payment-processing specs).
 *
 * <p>Every agreement here is TG residential, 11 months at INR 25,000 with an INR 50,000 deposit:
 * legal duty 0.4% x (275,000 + 50,000) = INR 1,300. The TG catalog's offer policy shows only a
 * single INR 100 stamp paper, pre-selected; it is below the duty, so paying for it needs the
 * acknowledgement, and it costs the base INR 499. The TG figures are UNVERIFIED and the test
 * profile allows unreviewed rules, exactly as local and beta deployments run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StampDutyCheckoutIntegrationTest {

  private static final String ORDERS_URL = "/v1/orders";
  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
  private static final ObjectMapper JSON = new ObjectMapper();

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("payment.razorpay.base-url", () -> WIREMOCK.baseUrl() + "/");
    registry.add("payment.razorpay.key-id", () -> "rzp_test_quote");
    registry.add("payment.razorpay.key-secret", () -> "it-quote-key");
    registry.add("payment.razorpay.webhook-secret", () -> "it-quote-mac");
    registry.add("delivery.public-base-url", () -> "https://app.example.test");
    registry.add("delivery.channels.email.enabled", () -> "true");
    registry.add("rules.stamp-duty.allow-unreviewed", () -> "true");
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

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    in.agreementmitra.support.TemplateCatalogFixture.seedEligible(jdbc);
  }

  // --- fixtures --------------------------------------------------------------

  private UUID createAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "12 MG Road, Hyderabad",
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

  private void stubOrderCreation(String orderId) {
    WIREMOCK.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .willReturn(
                okJson(
                    "{\"id\":\"" + orderId + "\",\"status\":\"created\",\"currency\":\"INR\"}")));
  }

  private ResponseEntity<String> quote(UUID agreementId, HttpHeaders headers) {
    return rest.exchange(
        "/api/agreements/" + agreementId + "/stamp-quote",
        HttpMethod.GET,
        new HttpEntity<>(headers == null ? new HttpHeaders() : headers),
        String.class);
  }

  private ResponseEntity<String> checkout(UUID agreementId, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/api/agreements/" + agreementId + "/payment/order",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private static Map<String, Object> acknowledged(long stampValue) {
    return Map.of(
        "stampValueMinorUnits",
        stampValue,
        "underStampAcknowledgement",
        Map.of("warningVersion", "under-stamp-v1"));
  }

  private Map<String, Object> frozenRow(UUID agreementId) {
    return jdbc.queryForMap("SELECT * FROM stamp_quote WHERE agreement_id = ?", agreementId);
  }

  private static JsonNode json(ResponseEntity<String> response) throws Exception {
    return JSON.readTree(response.getBody());
  }

  // --- the quote -------------------------------------------------------------

  @Test
  void theQuoteShowsDutyBreakdownRegistrationAndEveryOptionWithItsTotal() throws Exception {
    UUID agreementId = createAgreement();

    JsonNode quote = json(quote(agreementId, null));

    assertThat(quote.path("available").asBoolean()).isTrue();
    assertThat(quote.path("frozen").asBoolean()).isFalse();
    assertThat(quote.path("dutyMinorUnits").asLong()).isEqualTo(130_000L);
    assertThat(quote.path("registrationRequired").asBoolean()).isTrue();
    assertThat(quote.path("rule").path("id").asText()).isEqualTo("TG-lease-residential");
    assertThat(quote.path("rule").path("reviewed").asBoolean()).isFalse();
    assertThat(quote.path("breakdown").size()).isPositive();

    JsonNode options = quote.path("options");
    assertThat(options.size()).isEqualTo(1);
    assertThat(options.get(0).path("recommended").asBoolean()).isTrue();
    assertThat(options.get(0).path("stampValueMinorUnits").asLong()).isEqualTo(10_000L);
    assertThat(options.get(0).path("belowDuty").asBoolean()).isTrue();
    assertThat(options.get(0).path("totalMinorUnits").asLong()).isEqualTo(49_900L);
    assertThat(options.get(0).path("medium").asText()).isEqualTo("stamp-paper");
    assertThat(quote.toString()).doesNotContain("Asha").doesNotContain("MG Road");
  }

  @Test
  void aCallerWhoCannotSeeThePaymentCannotReadTheQuote() {
    UUID agreementId = createAgreement();
    String owner =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "quote-owner-" + UUID.randomUUID());
    String other =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "quote-other-" + UUID.randomUUID());
    HttpHeaders ownerHeaders = new HttpHeaders();
    ownerHeaders.setBearerAuth(owner);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + agreementId + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(ownerHeaders),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    HttpHeaders otherHeaders = new HttpHeaders();
    otherHeaders.setBearerAuth(other);

    ResponseEntity<String> refused = quote(agreementId, otherHeaders);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(refused.getBody()).doesNotContain("130000").doesNotContain("dutyMinorUnits");
    assertThat(quote(agreementId, ownerHeaders).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // --- checkout --------------------------------------------------------------

  @Test
  void theFullDutyValueIsNotOfferedForTelangana() throws Exception {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_NOT_OFFERED");

    ResponseEntity<String> refused =
        checkout(agreementId, Map.of("stampValueMinorUnits", 130_000L));

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(json(refused).path("reason").asText()).isEqualTo("NOT_AN_OPTION");
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo(ORDERS_URL)));
  }

  @Test
  void anAcknowledgedBelowDutyChoicePersistsTheAcknowledgementAndNoPersonalData() throws Exception {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_QUOTE_BELOW");

    ResponseEntity<String> session = checkout(agreementId, acknowledged(10_000L));

    assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json(session).path("amountMinorUnits").asLong()).isEqualTo(49_900L);
    assertThat(json(session).path("dutyMinorUnits").asLong()).isEqualTo(130_000L);
    assertThat(json(session).path("stampValueMinorUnits").asLong()).isEqualTo(10_000L);
    WIREMOCK.verify(
        1,
        postRequestedFor(urlEqualTo(ORDERS_URL))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("49900")));
    Map<String, Object> row = frozenRow(agreementId);
    assertThat(((Number) row.get("duty_minor_units")).longValue()).isEqualTo(130_000L);
    assertThat(((Number) row.get("stamp_value_minor_units")).longValue()).isEqualTo(10_000L);
    assertThat(row.get("rule_id")).isEqualTo("TG-lease-residential");
    assertThat(row.get("medium_id")).isEqualTo("stamp-paper");
    assertThat(row.get("below_duty")).isEqualTo(true);
    assertThat(row.get("ack_warning_version")).isEqualTo("under-stamp-v1");
    assertThat(row.get("ack_at")).isNotNull();
    assertThat(row.toString())
        .doesNotContain("Asha")
        .doesNotContain("Tara")
        .doesNotContain("asha@example.com")
        .doesNotContain("MG Road");
  }

  @Test
  void anInvalidOptionOrMissingAcknowledgementNeverCallsTheProvider() throws Exception {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_NEVER");

    ResponseEntity<String> notAnOption =
        checkout(agreementId, Map.of("stampValueMinorUnits", 5_000L));
    ResponseEntity<String> noAck = checkout(agreementId, Map.of("stampValueMinorUnits", 10_000L));
    ResponseEntity<String> stale =
        checkout(
            agreementId,
            Map.of(
                "stampValueMinorUnits",
                10_000L,
                "underStampAcknowledgement",
                Map.of("warningVersion", "under-stamp-v0")));
    ResponseEntity<String> noChoice = checkout(agreementId, Map.of());

    assertThat(List.of(notAnOption, noAck, stale, noChoice))
        .allSatisfy(
            r -> {
              assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(r.getBody()).contains("stamp-choice-invalid");
            });
    assertThat(json(notAnOption).path("reason").asText()).isEqualTo("NOT_AN_OPTION");
    assertThat(json(noAck).path("reason").asText()).isEqualTo("ACKNOWLEDGEMENT_REQUIRED");
    assertThat(json(stale).path("reason").asText()).isEqualTo("WARNING_VERSION_STALE");
    assertThat(json(stale).path("warningVersion").asText()).isEqualTo("under-stamp-v1");
    assertThat(json(noChoice).path("reason").asText()).isEqualTo("CHOICE_REQUIRED");
    WIREMOCK.verify(0, postRequestedFor(urlEqualTo(ORDERS_URL)));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE agreement_id = ?",
                Long.class,
                agreementId))
        .isZero();
  }

  @Test
  void resumingWithADifferentChoiceReturnsTheOriginalOrder() throws Exception {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_QUOTE_RESUME");
    checkout(agreementId, acknowledged(10_000L));

    ResponseEntity<String> resumed =
        checkout(agreementId, Map.of("stampValueMinorUnits", 130_000L));

    assertThat(json(resumed).path("orderId").asText()).isEqualTo("order_QUOTE_RESUME");
    assertThat(json(resumed).path("amountMinorUnits").asLong()).isEqualTo(49_900L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM stamp_quote WHERE agreement_id = ?", Long.class, agreementId))
        .isEqualTo(1L);
  }

  @Test
  void aTermsChangeAfterOrderingLeavesTheFrozenQuoteAndProgressUnchanged() throws Exception {
    UUID agreementId = createAgreement();
    stubOrderCreation("order_QUOTE_FROZEN");
    checkout(agreementId, acknowledged(10_000L));
    // Anything that would move a fresh quote: the recomputed duty would now be far higher.
    jdbc.update("UPDATE agreement SET monthly_rent = 90000.00 WHERE id = ?", agreementId);

    JsonNode progress =
        JSON.readTree(
            rest.getForEntity("/api/agreements/" + agreementId + "/payment", String.class)
                .getBody());
    JsonNode frozen = json(quote(agreementId, null));

    assertThat(progress.path("dutyMinorUnits").asLong()).isEqualTo(130_000L);
    assertThat(progress.path("stampValueMinorUnits").asLong()).isEqualTo(10_000L);
    assertThat(frozen.path("frozen").asBoolean()).isTrue();
    assertThat(frozen.path("dutyMinorUnits").asLong()).isEqualTo(130_000L);
    assertThat(frozen.path("options").size()).isEqualTo(1);
  }
}

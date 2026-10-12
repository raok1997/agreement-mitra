package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.PaymentEvents;
import in.agreementmitra.support.PaymentOrders;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TemplateCatalogFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the staff orders view's integration tests share: agreements, sessions, ledger attempts and
 * the request itself.
 *
 * <p>The suite shares one database, so the list holds other tests' orders too. Everything here
 * finds an entry by the test's own payment order id; nothing asserts a group's size.
 */
final class StaffOrdersFixture {

  static final String ORDERS = "/api/staff/payment-orders";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TestRestTemplate rest;
  private final JdbcTemplate jdbc;
  private final IdentityService identityService;
  private final HandoffService handoffService;
  private final SessionService sessionService;

  StaffOrdersFixture(
      TestRestTemplate rest,
      JdbcTemplate jdbc,
      IdentityService identityService,
      HandoffService handoffService,
      SessionService sessionService) {
    this.rest = rest;
    this.jdbc = jdbc;
    this.identityService = identityService;
    this.handoffService = handoffService;
    this.sessionService = sessionService;
  }

  /** Now, in the microseconds Postgres stores, so an instant reads back equal to itself. */
  static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** A gateway payment id no other test or run has used: both uniqueness rules span the suite. */
  static String paymentId() {
    return "pay_" + UUID.randomUUID().toString().replace("-", "");
  }

  String staffSession() {
    return StaffSessions.staffSession(
        identityService, handoffService, sessionService, jdbc, "orders-staff-" + UUID.randomUUID());
  }

  String customerSession() {
    return StaffSessions.customerSession(
        identityService, handoffService, sessionService, "orders-cust-" + UUID.randomUUID());
  }

  UUID agreement() {
    TemplateCatalogFixture.seedEligible(jdbc);
    Map<String, Object> body =
        Map.of(
            "state", TemplateCatalogFixture.ELIGIBLE_STATE,
            "type", TemplateCatalogFixture.TYPE,
            "propertyAddress", "12 Station Road, Warangal",
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
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  String trackingReference(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
  }

  /** An order of {@code status} on a new agreement, created now. */
  UUID order(String status) {
    return PaymentOrders.insert(jdbc, agreement(), status, null);
  }

  UUID agreementOf(UUID paymentOrderId) {
    return jdbc.queryForObject(
        "SELECT agreement_id FROM payment_order WHERE id = ?", UUID.class, paymentOrderId);
  }

  /** One attempt carrying a new payment id, received once at {@code at}. Returns the id. */
  String attempt(UUID paymentOrderId, String outcome, Instant at) {
    String paymentId = paymentId();
    attempt(paymentOrderId, outcome, paymentId, at);
    return paymentId;
  }

  /**
   * @param paymentId {@code null} for an event that carried no payment detail
   */
  void attempt(UUID paymentOrderId, String outcome, String paymentId, Instant at) {
    PaymentEvents.insert(
        jdbc,
        PaymentOrders.providerOrderId(paymentOrderId),
        paymentId,
        49900,
        "WEBHOOK",
        outcome,
        at,
        at);
  }

  ResponseEntity<String> get(String sessionOrNull) {
    return rest.exchange(
        ORDERS, HttpMethod.GET, new HttpEntity<>(headers(sessionOrNull)), String.class);
  }

  ResponseEntity<String> post(String sessionOrNull) {
    return rest.exchange(
        ORDERS, HttpMethod.POST, new HttpEntity<>(headers(sessionOrNull)), String.class);
  }

  private static HttpHeaders headers(String sessionOrNull) {
    HttpHeaders headers = new HttpHeaders();
    if (sessionOrNull != null) {
      headers.add(HttpHeaders.COOKIE, SessionCookie.header(sessionOrNull));
    }
    return headers;
  }

  /** The list as staff see it. */
  Listing list() {
    ResponseEntity<String> response = get(staffSession());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    try {
      return new Listing(response.getBody(), JSON.readTree(response.getBody()));
    } catch (JsonProcessingException e) {
      throw new AssertionError("the orders list was not JSON", e);
    }
  }

  /**
   * @param raw the body as sent, for a search no parser can soften
   */
  record Listing(String raw, JsonNode json) {

    Optional<JsonNode> stuck(UUID paymentOrderId) {
      return find("stuck", paymentOrderId);
    }

    Optional<JsonNode> notPaid(UUID paymentOrderId) {
      return find("notPaid", paymentOrderId);
    }

    /** The entry, which must be in the paid-but-stuck group and not in the other. */
    JsonNode stuckEntry(UUID paymentOrderId) {
      assertThat(notPaid(paymentOrderId)).isEmpty();
      return stuck(paymentOrderId)
          .orElseThrow(() -> new AssertionError("not in the paid-but-stuck group"));
    }

    /** The entry, which must be in the not-paid group and not in the other. */
    JsonNode notPaidEntry(UUID paymentOrderId) {
      assertThat(stuck(paymentOrderId)).isEmpty();
      return notPaid(paymentOrderId)
          .orElseThrow(() -> new AssertionError("not in the not-paid group"));
    }

    /** Where each of {@code paymentOrderIds} sits in {@code group}, in the group's order. */
    List<UUID> orderOf(String group, List<UUID> paymentOrderIds) {
      List<UUID> found = new ArrayList<>();
      for (JsonNode entry : json.get(group).get("entries")) {
        UUID id = UUID.fromString(entry.get("paymentOrderId").asText());
        if (paymentOrderIds.contains(id)) {
          found.add(id);
        }
      }
      return found;
    }

    private Optional<JsonNode> find(String group, UUID paymentOrderId) {
      List<JsonNode> matches = new ArrayList<>();
      for (JsonNode entry : json.get(group).get("entries")) {
        if (paymentOrderId.toString().equals(entry.get("paymentOrderId").asText())) {
          matches.add(entry);
        }
      }
      assertThat(matches).as("an order is one entry").hasSizeLessThanOrEqualTo(1);
      return matches.stream().findFirst();
    }
  }

  /** A JSON array of strings as a list. */
  static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }
}

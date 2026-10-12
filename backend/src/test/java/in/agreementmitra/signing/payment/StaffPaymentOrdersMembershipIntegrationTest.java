package in.agreementmitra.signing.payment;

import static in.agreementmitra.signing.payment.StaffOrdersFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.payment.StaffOrdersFixture.Listing;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.PaymentOrders;
import in.agreementmitra.support.Payments;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Which payment orders the staff orders view lists, and in which group, against real Postgres.
 *
 * <p>Every assertion looks up the test's own orders: the database is shared, so a group's size says
 * nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StaffPaymentOrdersMembershipIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private StaffOrdersFixture orders;
  private final Instant now = StaffOrdersFixture.now();

  @BeforeEach
  void fixture() {
    orders = new StaffOrdersFixture(rest, jdbc, identityService, handoffService, sessionService);
  }

  private Instant anHourAgo() {
    return now.minus(Duration.ofHours(1));
  }

  /** An order created {@code age} ago on a new agreement. */
  private UUID orderCreated(String status, Duration age) {
    return PaymentOrders.insert(
        jdbc, orders.agreement(), status, now.minus(age), UUID.randomUUID().toString());
  }

  private void markPaid(UUID paymentOrderId) {
    jdbc.update(
        "UPDATE payment_order SET status = 'PAID', confirmed_at = now() WHERE id = ?",
        paymentOrderId);
  }

  @Test
  void aPaidOrderIsInNeitherGroup() {
    UUID paid = PaymentOrders.insert(jdbc, orders.agreement(), "PAID", now);

    Listing listing = orders.list();

    assertThat(listing.stuck(paid)).isEmpty();
    assertThat(listing.notPaid(paid)).isEmpty();
  }

  @Test
  void anAgreementConfirmedByHandWithNoPaymentOrderIsNotListed() {
    UUID agreementId = orders.agreement();
    Payments.markPaid(jdbc, agreementId, StaffOrdersFixture.paymentId());

    assertThat(orders.list().raw()).doesNotContain(orders.trackingReference(agreementId));
  }

  @Test
  void aRecordingFailureIsListedAsStuckAndNotAsNotPaid() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "RECORDING_FAILED", anHourAgo());

    JsonNode entry = orders.list().stuckEntry(order);

    assertThat(strings(entry.get("stuckOutcomes"))).containsExactly("RECORDING_FAILED");
    assertThat(entry.get("status").asText()).isEqualTo("CREATED");
  }

  @Test
  void anOrderThatOnlyEverErroredIsListedAsStuck() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "ERRORED", anHourAgo());

    assertThat(strings(orders.list().stuckEntry(order).get("stuckOutcomes")))
        .containsExactly("ERRORED");
  }

  @Test
  void aStuckOrderIsListedHoweverOldItIs() {
    UUID order = orderCreated("EXPIRED", Duration.ofDays(90));
    orders.attempt(order, "AMOUNT_MISMATCH", now.minus(Duration.ofDays(89)));

    assertThat(strings(orders.list().stuckEntry(order).get("stuckOutcomes")))
        .containsExactly("AMOUNT_MISMATCH");
  }

  @Test
  void anAttemptWithoutAPaymentIdLeavesTheOrderNotPaid() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "AMOUNT_MISMATCH", null, anHourAgo());

    JsonNode entry = orders.list().notPaidEntry(order);

    assertThat(entry.get("stuckOutcomes")).isEmpty();
    assertThat(entry.get("attempts")).hasSize(1);
  }

  @Test
  void onlyAnAttemptWithAPaymentIdNamesAnOutcomeAndEveryAttemptIsShown() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    orders.attempt(order, "AMOUNT_MISMATCH", null, anHourAgo().plusSeconds(60));

    JsonNode entry = orders.list().stuckEntry(order);

    assertThat(strings(entry.get("stuckOutcomes"))).containsExactly("RECORDING_FAILED");
    assertThat(entry.get("attempts"))
        .extracting(attempt -> attempt.get("outcome").asText())
        .containsExactly("RECORDING_FAILED", "AMOUNT_MISMATCH");
  }

  @Test
  void twoStuckOutcomesAreOneEntryNamingBoth() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    orders.attempt(order, "DUPLICATE_REFERENCE", anHourAgo().plusSeconds(60));

    // stuckEntry also asserts the order appears once.
    assertThat(strings(orders.list().stuckEntry(order).get("stuckOutcomes")))
        .containsExactly("RECORDING_FAILED", "DUPLICATE_REFERENCE");
  }

  @Test
  void aStuckOrderSinceRecordedAsPaidIsInNeitherGroup() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    markPaid(order);

    Listing listing = orders.list();

    assertThat(listing.stuck(order)).isEmpty();
    assertThat(listing.notPaid(order)).isEmpty();
  }

  @Test
  void anOpenCheckoutAndAnExpiredOrderAreNotPaidAndOneOlderThanThirtyDaysIsNotListed() {
    UUID open = orderCreated("CREATED", Duration.ofHours(1));
    UUID expired = orderCreated("EXPIRED", Duration.ofDays(3));
    UUID old = orderCreated("EXPIRED", Duration.ofDays(31));

    Listing listing = orders.list();

    assertThat(listing.notPaidEntry(open).get("status").asText()).isEqualTo("CREATED");
    assertThat(listing.notPaidEntry(expired).get("status").asText()).isEqualTo("EXPIRED");
    assertThat(listing.notPaid(old)).isEmpty();
    assertThat(listing.stuck(old)).isEmpty();
  }

  @Test
  void stuckOrdersAreNewestAttemptFirst() {
    UUID attemptedAnHourAgo = orders.order("EXPIRED");
    UUID attemptedAMinuteAgo = orders.order("EXPIRED");
    orders.attempt(attemptedAnHourAgo, "RECORDING_FAILED", anHourAgo());
    orders.attempt(attemptedAMinuteAgo, "RECORDING_FAILED", now.minusSeconds(60));

    assertThat(orders.list().orderOf("stuck", List.of(attemptedAnHourAgo, attemptedAMinuteAgo)))
        .containsExactly(attemptedAMinuteAgo, attemptedAnHourAgo);
  }

  @Test
  void notPaidOrdersAreNewestCreatedFirst() {
    UUID createdYesterday = orderCreated("EXPIRED", Duration.ofDays(1));
    UUID createdAnHourAgo = orderCreated("EXPIRED", Duration.ofHours(1));

    assertThat(orders.list().orderOf("notPaid", List.of(createdYesterday, createdAnHourAgo)))
        .containsExactly(createdAnHourAgo, createdYesterday);
  }

  @Test
  void readingTheListTwiceGivesTheSameEntriesAndChangesNoRow() {
    UUID stuck = orders.order("CREATED");
    UUID notPaid = orders.order("EXPIRED");
    orders.attempt(stuck, "RECORDING_FAILED", anHourAgo());
    List<Map<String, Object>> before = rowsOf(stuck, notPaid);

    Listing first = orders.list();
    Listing second = orders.list();

    assertThat(second.stuckEntry(stuck)).isEqualTo(first.stuckEntry(stuck));
    assertThat(second.notPaidEntry(notPaid)).isEqualTo(first.notPaidEntry(notPaid));
    assertThat(rowsOf(stuck, notPaid)).isEqualTo(before);
  }

  /** Every order, ledger and alert row belonging to the two orders, as stored. */
  private List<Map<String, Object>> rowsOf(UUID first, UUID second) {
    List<Map<String, Object>> rows =
        new java.util.ArrayList<>(
            jdbc.queryForList(
                "SELECT * FROM payment_order WHERE id IN (?, ?) ORDER BY id", first, second));
    rows.addAll(
        jdbc.queryForList(
            "SELECT * FROM payment_event WHERE provider_order_id IN (?, ?) ORDER BY id",
            PaymentOrders.providerOrderId(first),
            PaymentOrders.providerOrderId(second)));
    rows.addAll(
        jdbc.queryForList(
            "SELECT * FROM staff_alert WHERE agreement_id IN (?, ?) ORDER BY id",
            orders.agreementOf(first),
            orders.agreementOf(second)));
    return rows;
  }
}

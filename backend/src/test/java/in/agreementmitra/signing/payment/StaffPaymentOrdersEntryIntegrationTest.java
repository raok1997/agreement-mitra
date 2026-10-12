package in.agreementmitra.signing.payment;

import static in.agreementmitra.signing.payment.StaffOrdersFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.payment.StaffOrdersFixture.Listing;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.PaymentEvents;
import in.agreementmitra.support.PaymentOrders;
import in.agreementmitra.support.Payments;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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
 * What one entry of the staff orders view carries, and what it withholds, against real Postgres.
 *
 * <p>Payment ids are generated per test: both payment uniqueness rules span the shared database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StaffPaymentOrdersEntryIntegrationTest {

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

  @Test
  void anEntryCarriesTheTrackingReferenceAndTheOrderIdAndNeverTheAgreementId() {
    UUID agreementId = orders.agreement();
    Instant createdAt = now.minus(Duration.ofMinutes(5));
    // The receipt production writes: the agreement id itself.
    UUID order =
        PaymentOrders.insert(jdbc, agreementId, "CREATED", createdAt, agreementId.toString());

    Listing listing = orders.list();
    JsonNode entry = listing.notPaidEntry(order);

    assertThat(entry.get("trackingReference").asText())
        .isEqualTo(orders.trackingReference(agreementId));
    assertThat(entry.get("paymentOrderId").asText()).isEqualTo(order.toString());
    assertThat(Instant.parse(entry.get("createdAt").asText())).isEqualTo(createdAt);
    assertThat(entry.get("amountMinorUnits").asLong()).isEqualTo(49900);
    assertThat(entry.get("currency").asText()).isEqualTo("INR");
    // A raw search, so a value under any key - or a receipt with a retry suffix - is caught.
    assertThat(listing.raw())
        .doesNotContain("\"agreementId\"", "\"receipt\"", "\"providerOrderId\"")
        .doesNotContain(agreementId.toString())
        .doesNotContain(PaymentOrders.providerOrderId(order));
  }

  @Test
  void anAmountMismatchCarriesTheOrderAmountAndTheAmountTheGatewayReported() {
    UUID order = orders.order("CREATED");
    PaymentEvents.insert(
        jdbc,
        PaymentOrders.providerOrderId(order),
        StaffOrdersFixture.paymentId(),
        4990,
        "WEBHOOK",
        "AMOUNT_MISMATCH",
        anHourAgo(),
        anHourAgo());

    JsonNode entry = orders.list().stuckEntry(order);

    assertThat(entry.get("amountMinorUnits").asLong()).isEqualTo(49900);
    assertThat(entry.get("attempts").get(0).get("amountMinorUnits").asLong()).isEqualTo(4990);
  }

  @Test
  void aRepeatedAttemptCarriesItsCountBothTimesAndTheRuleThatRefusedIt() {
    UUID order = orders.order("CREATED");
    String paymentId = StaffOrdersFixture.paymentId();
    Instant first = now.minus(Duration.ofHours(3));
    Instant last = now.minus(Duration.ofMinutes(10));
    PaymentEvents.insert(
        jdbc,
        PaymentOrders.providerOrderId(order),
        paymentId,
        49900,
        "INR",
        "RECONCILIATION",
        "RECORDING_FAILED",
        "payment_order_amount_check",
        "23514",
        first,
        last,
        7);

    JsonNode attempt = orders.list().stuckEntry(order).get("attempts").get(0);

    assertThat(attempt.get("attemptCount").asLong()).isEqualTo(7);
    assertThat(Instant.parse(attempt.get("firstReceivedAt").asText())).isEqualTo(first);
    assertThat(Instant.parse(attempt.get("lastReceivedAt").asText())).isEqualTo(last);
    assertThat(attempt.get("source").asText()).isEqualTo("RECONCILIATION");
    assertThat(attempt.get("providerPaymentId").asText()).isEqualTo(paymentId);
    assertThat(attempt.get("refusedRule").asText()).isEqualTo("payment_order_amount_check");
    assertThat(attempt.get("sqlState").asText()).isEqualTo("23514");
  }

  @Test
  void anAgreementPaidByHandUnderThisPaymentInUpperCaseIsPaidUnderThisPayment() {
    UUID order = orders.order("CREATED");
    String paymentId = orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    Payments.markPaid(jdbc, orders.agreementOf(order), paymentId.toUpperCase(Locale.ROOT));

    assertThat(orders.list().stuckEntry(order).get("agreementPayment").asText())
        .isEqualTo("PAID_THIS_PAYMENT");
  }

  @Test
  void anAgreementPaidUnderAnotherPaymentSaysSoAndThatReferenceIsNotInTheResponse() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    String otherReference = StaffOrdersFixture.paymentId();
    Payments.markPaid(jdbc, orders.agreementOf(order), otherReference);

    Listing listing = orders.list();

    assertThat(listing.stuckEntry(order).get("agreementPayment").asText())
        .isEqualTo("PAID_OTHER_PAYMENT");
    assertThat(listing.raw()).doesNotContainIgnoringCase(otherReference);
  }

  @Test
  void aWaivedAgreementIsReportedAsWaived() {
    UUID order = orders.order("EXPIRED");
    Payments.waive(jdbc, orders.agreementOf(order));

    assertThat(orders.list().notPaidEntry(order).get("agreementPayment").asText())
        .isEqualTo("WAIVED");
  }

  @Test
  void anUnpaidAgreementIsReportedAsUnpaid() {
    UUID order = orders.order("EXPIRED");

    assertThat(orders.list().notPaidEntry(order).get("agreementPayment").asText())
        .isEqualTo("UNPAID");
  }

  @Test
  void aReusedPaymentIdHeldByAnotherAgreementNamesItsTrackingReference() {
    UUID order = orders.order("CREATED");
    String paymentId = orders.attempt(order, "DUPLICATE_REFERENCE", anHourAgo());
    UUID holder = orders.agreement();
    // Recorded by hand, in the form staff type and the unique index stores.
    Payments.markPaid(jdbc, holder, " " + paymentId.toUpperCase(Locale.ROOT) + " ");

    Listing listing = orders.list();

    assertThat(strings(listing.stuckEntry(order).get("heldBy")))
        .containsExactly(orders.trackingReference(holder));
    assertThat(listing.raw()).doesNotContain(holder.toString());
  }

  @Test
  void aReusedPaymentIdHeldByAnotherAgreementsPaidOrderNamesThatAgreement() {
    UUID order = orders.order("CREATED");
    String paymentId = orders.attempt(order, "DUPLICATE_REFERENCE", anHourAgo());
    UUID holder = orders.agreement();
    PaymentOrders.insertPaidUnder(jdbc, holder, paymentId);

    Listing listing = orders.list();

    assertThat(strings(listing.stuckEntry(order).get("heldBy")))
        .containsExactly(orders.trackingReference(holder));
    assertThat(listing.raw()).doesNotContain(holder.toString());
  }

  /** The unique index treats the two spellings as one reference, so one agreement holds both. */
  @Test
  void twoOrdersRefusedForOnePaymentIdInDifferentCaseBothNameTheHolder() {
    UUID lower = orders.order("CREATED");
    UUID upper = orders.order("CREATED");
    String paymentId = orders.attempt(lower, "DUPLICATE_REFERENCE", anHourAgo());
    orders.attempt(upper, "DUPLICATE_REFERENCE", paymentId.toUpperCase(Locale.ROOT), anHourAgo());
    UUID holder = orders.agreement();
    Payments.markPaid(jdbc, holder, paymentId.toUpperCase(Locale.ROOT));

    Listing listing = orders.list();

    assertThat(strings(listing.stuckEntry(lower).get("heldBy")))
        .containsExactly(orders.trackingReference(holder));
    assertThat(strings(listing.stuckEntry(upper).get("heldBy")))
        .containsExactly(orders.trackingReference(holder));
  }

  @Test
  void aReusedPaymentIdNothingHoldsAnyMoreNamesNoHolder() {
    UUID order = orders.order("CREATED");
    orders.attempt(order, "DUPLICATE_REFERENCE", anHourAgo());

    assertThat(orders.list().stuckEntry(order).get("heldBy")).isEmpty();
  }

  @Test
  void aRecordingFailureNamesNoHolderEvenWhenItsPaymentIdIsHeld() {
    UUID order = orders.order("CREATED");
    String paymentId = orders.attempt(order, "RECORDING_FAILED", anHourAgo());
    Payments.markPaid(jdbc, orders.agreement(), paymentId);

    assertThat(orders.list().stuckEntry(order).get("heldBy")).isEmpty();
  }
}

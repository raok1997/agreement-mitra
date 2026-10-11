package in.agreementmitra.signing.staffalert;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import in.agreementmitra.AgreementIds;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.LogCapture;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.PaymentEvents;
import in.agreementmitra.support.PaymentOrders;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.TemplateCatalogFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The staff alert against real Postgres and a stubbed channel: which paid orders raise an alert,
 * how one is claimed, sent, retried and failed, and that nothing personal or secret leaves.
 *
 * <p>The scheduled job stays off (test profile); every step is called directly with an explicit
 * time, so a retry is "advance the clock", not "wait". Payment orders are inserted directly: how an
 * order becomes paid, or surplus, is the Razorpay tests' subject. One test here does go through the
 * gateway webhook, to join the two ends.
 *
 * <p>Every assertion is scoped to the test's own agreement, because the sweep sees every paid order
 * in this context's database, including the ones other tests in this class left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StaffAlertIntegrationTest {

  private static final String TOKEN = "tok-9f3aSECRETc41d";
  private static final String HOOK_PATH = "/api/webhooks/1/" + TOKEN;
  private static final String SITE = "https://app.example.test";
  private static final String PARTY_FIRST_NAME = "Ashalata";
  private static final String CITY = "Warangal";
  private static final String GATEWAY_WEBHOOK_KEY = "it-alert-wh-mac";

  private static final WireMockServer CHANNEL = new WireMockServer(options().dynamicPort());

  static {
    CHANNEL.start();
  }

  private static String hookUrl() {
    return CHANNEL.baseUrl() + HOOK_PATH;
  }

  @DynamicPropertySource
  static void channelProperties(DynamicPropertyRegistry registry) {
    registry.add("staff-alert.discord.webhook-url", StaffAlertIntegrationTest::hookUrl);
    registry.add("delivery.public-base-url", () -> SITE);
    // Fabricated. Lets one test deliver a signed gateway webhook, so the surplus path is exercised
    // from the confirmation to the alert rather than from an inserted row.
    registry.add("payment.razorpay.webhook-secret", () -> GATEWAY_WEBHOOK_KEY);
  }

  @AfterAll
  static void stopChannel() {
    CHANNEL.stop();
  }

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StaffAlertDispatcher dispatcher;

  @MockitoSpyBean private StaffNotifier notifier;
  @MockitoSpyBean private StaffAlertPersistence persistence;

  /**
   * Microseconds, as Postgres stores them. On a nanosecond clock the driver rounds a bound instant
   * half-up, so an untruncated {@code now} could read back later than itself and look "not due".
   */
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @BeforeEach
  void reset() {
    CHANNEL.resetAll();
    TemplateCatalogFixture.seedEligible(jdbc);
  }

  // --- fixtures ----------------------------------------------------------------

  private UUID createAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "12 Station Road, " + CITY,
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    Map.of(
                        "firstName", PARTY_FIRST_NAME,
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

  /** An agreement with a gateway-paid order and its pending alert, due at {@link #now}. */
  private UUID paidAndEnqueued() {
    UUID agreementId = createAgreement();
    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(60));
    dispatcher.enqueue(now);
    assertThat(alertCount(agreementId)).isEqualTo(1);
    return agreementId;
  }

  private void channelAnswers(int status) {
    CHANNEL.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(status)));
  }

  /** Paid-order alerts for the agreement - at most one, keyed by the agreement itself. */
  private int alertCount(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM staff_alert WHERE agreement_id = ? AND kind = 'ORDER_PAID'",
        Integer.class,
        agreementId);
  }

  /** Duplicate-payment alerts for the agreement - one per surplus order, keyed by the order. */
  private int duplicateAlertCount(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM staff_alert WHERE agreement_id = ? AND kind = 'DUPLICATE_PAYMENT'",
        Integer.class,
        agreementId);
  }

  /** Alerts of every kind for the agreement. */
  private int anyKindAlertCount(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM staff_alert WHERE agreement_id = ?", Integer.class, agreementId);
  }

  /** The kinds of alert held for one subject - an agreement or a payment order. */
  private List<String> kindsFor(UUID subjectId) {
    return jdbc.queryForList(
        "SELECT kind FROM staff_alert WHERE id = ? ORDER BY kind", String.class, subjectId);
  }

  private static StaffAlertKey paid(UUID agreementId) {
    return new StaffAlertKey(agreementId, StaffAlertKind.ORDER_PAID);
  }

  private static StaffAlertKey duplicate(UUID paymentOrderId) {
    return new StaffAlertKey(paymentOrderId, StaffAlertKind.DUPLICATE_PAYMENT);
  }

  private static StaffAlertKey notRecorded(UUID paymentOrderId) {
    return new StaffAlertKey(paymentOrderId, StaffAlertKind.PAYMENT_NOT_RECORDED);
  }

  private static StaffAlertKey amountMismatch(UUID paymentOrderId) {
    return new StaffAlertKey(paymentOrderId, StaffAlertKind.PAYMENT_AMOUNT_MISMATCH);
  }

  /** One alert by its key. One id can hold an alert of each kind, so the id alone is not enough. */
  private Map<String, Object> alert(StaffAlertKey key) {
    return jdbc.queryForMap(
        "SELECT * FROM staff_alert WHERE id = ? AND kind = ?", key.id(), key.kind().name());
  }

  private String statusOf(StaffAlertKey key) {
    return (String) alert(key).get("status");
  }

  private int attemptsOf(StaffAlertKey key) {
    return ((Number) alert(key).get("attempts")).intValue();
  }

  private Instant nextAttemptOf(StaffAlertKey key) {
    return ((Timestamp) alert(key).get("next_attempt_at")).toInstant();
  }

  /** A ledger row for the order's attempt, from the webhook, received once at {@code at}. */
  private String ledgerRow(UUID paymentOrderId, String outcome, Instant at) {
    return PaymentEvents.insert(
        jdbc, PaymentOrders.providerOrderId(paymentOrderId), "WEBHOOK", outcome, at, at);
  }

  /**
   * The ledger look-back swallows its own failure so it cannot stop the sweep - which would let a
   * broken query read here as "no alert raised". A test of that look-back asserts it ran.
   */
  private void assertTheLedgerLookBackRan() {
    assertThat(logs.messages()).noneMatch(m -> m.contains("payment event ledger"));
  }

  private UUID surplusOrder(UUID agreementId, Instant confirmedAt) {
    return PaymentOrders.insert(jdbc, agreementId, "PAID", confirmedAt, true);
  }

  private String trackingReferenceOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private static final String PAID_LEAD = "Paid order waiting for a stamp";
  private static final String DUPLICATE_LEAD =
      "Possible duplicate payment - check before refunding";
  private static final String NOT_RECORDED_LEAD =
      "Payment captured but not recorded - check before recording or refunding";
  private static final String MISMATCH_LEAD =
      "Payment refused: the amount paid differs from the order - check before recording or"
          + " refunding";

  /** Requests the channel received that name this agreement's tracking reference, of any kind. */
  private List<LoggedRequest> requestsFor(UUID agreementId) {
    return CHANNEL.findAll(
        postRequestedFor(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(containing(trackingReferenceOf(agreementId))));
  }

  /** The same, narrowed to the messages that open with {@code lead}. */
  private List<LoggedRequest> requestsFor(UUID agreementId, String lead) {
    return CHANNEL.findAll(
        postRequestedFor(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(containing(trackingReferenceOf(agreementId)))
            .withRequestBody(containing(lead)));
  }

  private void assertNothingSecretWasLogged(UUID agreementId) {
    List<String> everything = new java.util.ArrayList<>(logs.messages());
    everything.addAll(logs.throwableMessages());
    assertThat(everything)
        .noneMatch(line -> line.contains(hookUrl()))
        .noneMatch(line -> line.contains(TOKEN))
        .noneMatch(line -> line.contains(agreementId.toString()));
  }

  // --- which orders raise an alert (6.3) ---------------------------------------

  @Test
  void aPaidOrderRaisesOnePendingAlertHoweverOftenTheSweepRuns() {
    UUID agreementId = createAgreement();
    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(60));

    dispatcher.enqueue(now);
    dispatcher.enqueue(now.plusSeconds(30));

    assertThat(alertCount(agreementId)).isEqualTo(1);
    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(attemptsOf(paid(agreementId))).isZero();
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  @Test
  void aSecondPaidOrderForTheSameAgreementRaisesNoSecondAlert() {
    UUID agreementId = paidAndEnqueued();

    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(10));
    dispatcher.enqueue(now.plusSeconds(30));

    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aGatewayPaymentAfterAManualStaffConfirmationRaisesAnAlert() {
    UUID agreementId = createAgreement();
    Payments.markPaid(jdbc, agreementId, "NEFT-" + UUID.randomUUID());
    dispatcher.enqueue(now);
    assertThat(alertCount(agreementId)).isZero();

    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(60));
    dispatcher.enqueue(now);

    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void anOrderPaidBeforeTheLookBackWindowRaisesNoAlert() {
    UUID agreementId = createAgreement();
    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minus(Duration.ofHours(25)));

    dispatcher.enqueue(now);

    assertThat(alertCount(agreementId)).isZero();
  }

  @Test
  void unpaidPaymentOrdersRaiseNoAlert() {
    UUID agreementId = createAgreement();
    // No confirmation attempt is in the ledger for any of them.
    PaymentOrders.insert(jdbc, agreementId, "FAILED", null);
    PaymentOrders.insert(jdbc, agreementId, "EXPIRED", null);
    PaymentOrders.insert(jdbc, agreementId, "CREATED", null);

    dispatcher.enqueue(now);

    assertThat(anyKindAlertCount(agreementId)).isZero();
    assertTheLedgerLookBackRan();
  }

  @Test
  void anAgreementWithNoGatewayOrderRaisesNoAlertWhateverItsPaymentState() {
    UUID unpaid = createAgreement();
    UUID staffConfirmed = createAgreement();
    Payments.markPaid(jdbc, staffConfirmed, "NEFT-" + UUID.randomUUID());
    UUID waived = createAgreement();
    Payments.waive(jdbc, waived);

    dispatcher.enqueue(now);

    assertThat(anyKindAlertCount(unpaid)).isZero();
    assertThat(anyKindAlertCount(staffConfirmed)).isZero();
    assertThat(anyKindAlertCount(waived)).isZero();
  }

  // --- dispatch (6.4) ----------------------------------------------------------

  @Test
  void aDueAlertIsPostedOnceAndMarkedSentWithNothingPersonalInIt() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(204);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("SENT");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(alert(paid(agreementId)).get("sent_at")).isNotNull();
    List<LoggedRequest> requests = requestsFor(agreementId);
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getBodyAsString())
        .contains(trackingReferenceOf(agreementId))
        .contains("(TG)")
        .contains(SITE)
        .doesNotContain(agreementId.toString())
        .doesNotContain(PARTY_FIRST_NAME)
        .doesNotContain("Tara")
        .doesNotContain(CITY);

    // Sent is terminal: a later sweep neither re-sends nor re-enqueues it.
    dispatcher.enqueue(now.plusSeconds(60));
    dispatcher.dispatchOne(paid(agreementId), now.plusSeconds(60));
    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aServerErrorIsRetriedAfterTheBackoffAndThenSent() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(nextAttemptOf(paid(agreementId))).isAfter(now);

    // Not due yet: nothing happens inside the backoff.
    channelAnswers(204);
    dispatcher.dispatchOne(paid(agreementId), now.plusSeconds(10));
    assertThat(requestsFor(agreementId)).hasSize(1);

    dispatcher.dispatchOne(paid(agreementId), now.plus(StaffAlertBackoff.after(1)).plusSeconds(1));

    assertThat(statusOf(paid(agreementId))).isEqualTo("SENT");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(2);
    assertThat(requestsFor(agreementId)).hasSize(2);
  }

  @Test
  void aRateLimitedAlertStaysPendingForLater() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(429);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(nextAttemptOf(paid(agreementId))).isAfter(now);
  }

  @Test
  void aNotFoundFailsTheAlertAfterOneAttempt() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(404);

    dispatcher.dispatchOne(paid(agreementId), now);
    dispatcher.dispatchOne(paid(agreementId), now.plus(Duration.ofHours(2)));

    assertThat(statusOf(paid(agreementId))).isEqualTo("FAILED");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(logs.events())
        .filteredOn(e -> e.getLevel() == Level.ERROR)
        .extracting(e -> e.getFormattedMessage())
        .anyMatch(m -> m.contains(AgreementIds.redact(agreementId)) && m.contains("404"));
    assertNothingSecretWasLogged(agreementId);
  }

  @Test
  void aRedirectFailsTheAlertAndIsNotFollowed() {
    UUID agreementId = paidAndEnqueued();
    CHANNEL.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
    CHANNEL.stubFor(post(urlPathEqualTo("/elsewhere")).willReturn(aResponse().withStatus(204)));

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("FAILED");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    CHANNEL.verify(0, postRequestedFor(urlPathEqualTo("/elsewhere")));
  }

  @Test
  void aTransientFailureOnTheLastAttemptFailsTheAlert() {
    UUID agreementId = paidAndEnqueued();
    jdbc.update(
        "UPDATE staff_alert SET attempts = ? WHERE id = ? AND kind = 'ORDER_PAID'",
        StaffAlertBackoff.MAX_ATTEMPTS - 1,
        agreementId);
    channelAnswers(500);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("FAILED");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(StaffAlertBackoff.MAX_ATTEMPTS);
    assertThat(requestsFor(agreementId)).hasSize(1);
  }

  @Test
  void anAlertLeftPendingAfterItsLastAttemptIsFailedWithoutAnotherSend() {
    UUID agreementId = paidAndEnqueued();
    jdbc.update(
        "UPDATE staff_alert SET attempts = ? WHERE id = ? AND kind = 'ORDER_PAID'",
        StaffAlertBackoff.MAX_ATTEMPTS,
        agreementId);
    channelAnswers(204);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("FAILED");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(StaffAlertBackoff.MAX_ATTEMPTS);
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  @Test
  void anAlertPendingForMoreThanADayIsFailedWithoutBeingSent() {
    UUID agreementId = paidAndEnqueued();
    Instant stale = now.minus(Duration.ofHours(25));
    jdbc.update(
        "UPDATE staff_alert SET created_at = ?, next_attempt_at = ? WHERE id = ?"
            + " AND kind = 'ORDER_PAID'",
        Timestamp.from(stale),
        Timestamp.from(stale),
        agreementId);
    channelAnswers(204);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("FAILED");
    assertThat(attemptsOf(paid(agreementId))).isZero();
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  // --- the claim (6.5) ---------------------------------------------------------

  @Test
  void aRowIsClaimedOnceForTheAttemptCountTheCallerSaw() {
    UUID agreementId = paidAndEnqueued();
    Instant next = now.plus(StaffAlertBackoff.after(1));

    assertThat(persistence.claim(paid(agreementId), 0, now, next)).isTrue();
    assertThat(persistence.claim(paid(agreementId), 0, now, next)).isFalse();
    // Even once the lease has lapsed, a caller holding the old attempt count cannot claim.
    assertThat(persistence.claim(paid(agreementId), 0, next.plusSeconds(1), next.plusSeconds(90)))
        .isFalse();

    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(nextAttemptOf(paid(agreementId))).isEqualTo(next);
  }

  @Test
  void aClaimedRowCannotBeClaimedAgainUntilItsLeaseLapses() {
    UUID agreementId = paidAndEnqueued();
    Instant lease = now.plus(StaffAlertBackoff.after(1));
    assertThat(persistence.claim(paid(agreementId), 0, now, lease)).isTrue();

    // The right attempt count, but the lease still holds: the SQL guard refuses, not the caller.
    assertThat(persistence.claim(paid(agreementId), 1, now.plusSeconds(10), lease.plusSeconds(60)))
        .isFalse();
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);

    assertThat(persistence.claim(paid(agreementId), 1, lease.plusSeconds(1), lease.plusSeconds(60)))
        .isTrue();
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(2);
  }

  @Test
  void twoDispatchesAtTheSameInstantPostOnce() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500); // stays PENDING, so only the lease the first claim wrote stops the second

    dispatcher.dispatchOne(paid(agreementId), now);
    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
  }

  /**
   * Discord accepted the message and the database then refused to record it. That is a recording
   * failure, not a send failure: the row must stay pending (a duplicate later is accepted) and must
   * never be marked failed.
   */
  @Test
  void aDeliveredAlertThatCannotBeRecordedStaysPendingAndIsNotReportedAsASendFailure() {
    UUID agreementId = paidAndEnqueued();
    jdbc.update(
        "UPDATE staff_alert SET attempts = ? WHERE id = ? AND kind = 'ORDER_PAID'",
        StaffAlertBackoff.MAX_ATTEMPTS - 1,
        agreementId);
    channelAnswers(204);
    doThrow(new IllegalStateException("database unavailable"))
        .when(persistence)
        .markSent(any(), org.mockito.ArgumentMatchers.anyInt(), any());

    assertThatThrownBy(() -> dispatcher.dispatchOne(paid(agreementId), now))
        .isInstanceOf(IllegalStateException.class);

    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }

  // --- nothing secret is logged (6.6) ------------------------------------------

  @Test
  void aServerErrorLogsNeitherTheWebhookUrlNorTheAgreementId() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500);

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(logs.messages()).anyMatch(m -> m.contains(AgreementIds.redact(agreementId)));
    assertNothingSecretWasLogged(agreementId);
  }

  @Test
  void aHungChannelLogsNeitherTheWebhookUrlNorTheAgreementId() {
    UUID agreementId = paidAndEnqueued();
    CHANNEL.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .willReturn(aResponse().withStatus(204).withFixedDelay(2_000)));
    // The real adapter, with a read timeout a test can afford; production's is seven seconds.
    DiscordStaffNotifier impatient =
        new DiscordStaffNotifier(hookUrl(), Duration.ofSeconds(1), Duration.ofMillis(300));
    doAnswer(
            call -> {
              impatient.send(call.getArgument(0));
              return null;
            })
        .when(notifier)
        .send(any());

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(logs.messages()).anyMatch(m -> m.contains("Staff alert send failed"));
    assertNothingSecretWasLogged(agreementId);
  }

  @Test
  void anUnexpectedErrorCarryingTheUrlIsRetriedAndNeverLogged() {
    UUID agreementId = paidAndEnqueued();
    doThrow(new IllegalStateException("I/O error on POST request for \"" + hookUrl() + "\""))
        .when(notifier)
        .send(any());

    dispatcher.dispatchOne(paid(agreementId), now);

    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(logs.messages()).anyMatch(m -> m.contains("IllegalStateException"));
    assertNothingSecretWasLogged(agreementId);
  }

  // --- one full sweep (6.7) ----------------------------------------------------

  @Test
  void aFullSweepRecordsAndSendsAFreshPaidOrder() {
    // Settle everything earlier tests left behind, so the sweep's batch is this test's own row.
    settleEverythingLeftBehind();
    jdbc.update("UPDATE staff_alert SET status = 'SENT', sent_at = now() WHERE status = 'PENDING'");
    UUID agreementId = createAgreement();
    PaymentOrders.insert(jdbc, agreementId, "PAID", Instant.now().minusSeconds(5));
    channelAnswers(204);

    dispatcher.dispatchDue();

    assertThat(statusOf(paid(agreementId))).isEqualTo("SENT");
    assertThat(attemptsOf(paid(agreementId))).isEqualTo(1);
    assertThat(requestsFor(agreementId)).hasSize(1);
    CHANNEL.verify(1, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  /**
   * Give every paid order in this context's database a terminal alert of its kind - paid-order for
   * a credited order, duplicate-payment for a surplus one - and settle every alert the ledger
   * look-back raises for the orders other tests left unrecorded, so a full sweep's batch is the
   * calling test's own rows.
   */
  private void settleEverythingLeftBehind() {
    dispatcher.enqueue(Instant.now());
    jdbc.update(
        "INSERT INTO staff_alert (id, kind, agreement_id, status, attempts, next_attempt_at,"
            + " created_at, sent_at) SELECT DISTINCT agreement_id, 'ORDER_PAID', agreement_id,"
            + " 'SENT', 1, now(), now(), now() FROM payment_order"
            + " WHERE status = 'PAID' AND NOT surplus ON CONFLICT DO NOTHING");
    jdbc.update(
        "INSERT INTO staff_alert (id, kind, agreement_id, status, attempts, next_attempt_at,"
            + " created_at, sent_at) SELECT id, 'DUPLICATE_PAYMENT', agreement_id, 'SENT', 1,"
            + " now(), now(), now() FROM payment_order"
            + " WHERE status = 'PAID' AND surplus ON CONFLICT DO NOTHING");
    jdbc.update("UPDATE staff_alert SET status = 'SENT', sent_at = now() WHERE status = 'PENDING'");
  }

  // --- the duplicate-payment alert (double-charge-invisible-to-staff 6.6) --------

  @Test
  void aSurplusOrderRaisesOnePendingDuplicatePaymentAlertHoweverOftenTheSweepRuns() {
    UUID agreementId = paidAndEnqueued();
    UUID surplus = surplusOrder(agreementId, now.minusSeconds(10));

    dispatcher.enqueue(now);
    dispatcher.enqueue(now.plusSeconds(30));

    assertThat(duplicateAlertCount(agreementId)).isEqualTo(1);
    Map<String, Object> row = alert(duplicate(surplus));
    assertThat(row.get("kind")).isEqualTo("DUPLICATE_PAYMENT");
    assertThat(row.get("agreement_id")).isEqualTo(agreementId);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(attemptsOf(duplicate(surplus))).isZero();
    // The agreement's own paid-order alert is untouched, and nothing has been sent yet.
    assertThat(alertCount(agreementId)).isEqualTo(1);
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  @Test
  void aThirdPaymentRaisesItsOwnDuplicatePaymentAlert() {
    UUID agreementId = paidAndEnqueued();
    UUID second = surplusOrder(agreementId, now.minusSeconds(20));
    UUID third = surplusOrder(agreementId, now.minusSeconds(10));

    dispatcher.enqueue(now);

    assertThat(duplicateAlertCount(agreementId)).isEqualTo(2);
    assertThat(statusOf(duplicate(second))).isEqualTo("PENDING");
    assertThat(statusOf(duplicate(third))).isEqualTo("PENDING");
    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aPaidOrderThatIsNotSurplusRaisesNoDuplicatePaymentAlert() {
    UUID agreementId = paidAndEnqueued();
    // A second paid order that was NOT marked surplus (paid before the mark existed).
    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(10));

    dispatcher.enqueue(now.plusSeconds(30));

    assertThat(duplicateAlertCount(agreementId)).isZero();
    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aSurplusOrderPaidBeforeTheLookBackWindowRaisesNoAlert() {
    UUID agreementId = createAgreement();
    surplusOrder(agreementId, now.minus(Duration.ofHours(25)));

    dispatcher.enqueue(now);

    assertThat(duplicateAlertCount(agreementId)).isZero();
    assertThat(alertCount(agreementId)).isZero();
  }

  /**
   * Staff confirmed by hand, then the gateway captured a payment too: the order is surplus, and it
   * raises the duplicate-payment alert <em>instead of</em> "paid order waiting for a stamp" - which
   * would announce work on an agreement staff already handled.
   */
  @Test
  void aSurplusOrderAfterAManualStaffConfirmationRaisesOnlyTheDuplicatePaymentAlert() {
    UUID agreementId = createAgreement();
    Payments.markPaid(jdbc, agreementId, "NEFT-" + UUID.randomUUID());
    surplusOrder(agreementId, now.minusSeconds(60));

    dispatcher.enqueue(now);

    assertThat(duplicateAlertCount(agreementId)).isEqualTo(1);
    assertThat(alertCount(agreementId)).isZero();
  }

  @Test
  void aDueDuplicatePaymentAlertIsPostedOnceWithItsOwnLeadAndMarkedSent() {
    UUID agreementId = paidAndEnqueued();
    UUID surplus = surplusOrder(agreementId, now.minusSeconds(10));
    dispatcher.enqueue(now);
    channelAnswers(204);

    dispatcher.dispatchOne(duplicate(surplus), now);

    assertThat(statusOf(duplicate(surplus))).isEqualTo("SENT");
    assertThat(attemptsOf(duplicate(surplus))).isEqualTo(1);
    assertThat(alert(duplicate(surplus)).get("sent_at")).isNotNull();
    // Only the duplicate-payment alert was dispatched; the paid-order one is still pending.
    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(requestsFor(agreementId, PAID_LEAD)).isEmpty();
    List<LoggedRequest> requests = requestsFor(agreementId, DUPLICATE_LEAD);
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getBodyAsString())
        .contains(trackingReferenceOf(agreementId))
        .contains("(TG)")
        .contains(SITE)
        .doesNotContain(agreementId.toString())
        .doesNotContain(surplus.toString())
        .doesNotContain(PaymentOrders.providerOrderId(surplus))
        .doesNotContain(PARTY_FIRST_NAME)
        .doesNotContain(CITY);

    // Sent is terminal: a later sweep neither re-sends nor re-enqueues it.
    dispatcher.enqueue(now.plusSeconds(60));
    dispatcher.dispatchOne(duplicate(surplus), now.plusSeconds(60));
    assertThat(requestsFor(agreementId, DUPLICATE_LEAD)).hasSize(1);
    assertThat(duplicateAlertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aFailedDuplicatePaymentAlertNamesItsKindAndNeverItsKey() {
    UUID agreementId = paidAndEnqueued();
    UUID surplus = surplusOrder(agreementId, now.minusSeconds(10));
    dispatcher.enqueue(now);
    channelAnswers(404);

    dispatcher.dispatchOne(duplicate(surplus), now);

    assertThat(statusOf(duplicate(surplus))).isEqualTo("FAILED");
    assertThat(statusOf(paid(agreementId))).isEqualTo("PENDING");
    assertThat(logs.events())
        .filteredOn(e -> e.getLevel() == Level.ERROR)
        .extracting(e -> e.getFormattedMessage())
        .anyMatch(
            m ->
                m.contains("DUPLICATE_PAYMENT")
                    && m.contains(AgreementIds.redact(agreementId))
                    && m.contains("404"));
    assertThat(logs.messages()).noneMatch(m -> m.contains(surplus.toString()));
    assertNothingSecretWasLogged(agreementId);
  }

  /** The row an earlier database carried over: its key is unchanged, so nothing is raised again. */
  @Test
  void aSentAlertKeyedByTheAgreementBlocksANewPaidOrderAlert() {
    UUID agreementId = createAgreement();
    jdbc.update(
        "INSERT INTO staff_alert (id, kind, agreement_id, status, attempts, next_attempt_at,"
            + " created_at, sent_at) VALUES (?, 'ORDER_PAID', ?, 'SENT', 1, now(), now(), now())",
        agreementId,
        agreementId);
    PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(60));

    dispatcher.enqueue(now);

    assertThat(alertCount(agreementId)).isEqualTo(1);
    assertThat(statusOf(paid(agreementId))).isEqualTo("SENT");
  }

  /**
   * The kind names are typed twice: in {@link StaffAlertKind} and in the {@code
   * staff_alert_kind_check} constraint of an immutable migration. A kind added to the enum without
   * a migration would be refused on insert, and for an unrecorded-payment kind that rolls back
   * every such alert of the sweep.
   */
  @Test
  void theSchemaAcceptsEveryAlertKind() {
    UUID agreementId = createAgreement();

    for (StaffAlertKind kind : StaffAlertKind.values()) {
      // A paid-order alert must be keyed by its agreement; any other kind by some order.
      UUID id = kind == StaffAlertKind.ORDER_PAID ? agreementId : UUID.randomUUID();
      jdbc.update(
          "INSERT INTO staff_alert (id, kind, agreement_id, status, attempts, next_attempt_at,"
              + " created_at, sent_at) VALUES (?, ?, ?, 'SENT', 1, now(), now(), now())",
          id,
          kind.name(),
          agreementId);
    }

    assertThat(anyKindAlertCount(agreementId)).isEqualTo(StaffAlertKind.values().length);
  }

  // --- unrecorded payments, read from the ledger (payment-recording-failure-staff-alert) -------

  /** An agreement with one outstanding gateway order. */
  private UUID outstandingOrder(UUID agreementId) {
    return PaymentOrders.insert(jdbc, agreementId, "CREATED", null);
  }

  @Test
  void aRecordingFailureRaisesOnePendingPaymentNotRecordedAlertKeyedByTheOrder() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
    Map<String, Object> row = alert(notRecorded(order));
    assertThat(row.get("agreement_id")).isEqualTo(agreementId);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(anyKindAlertCount(agreementId)).isEqualTo(1);
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  @Test
  void aRefusedDuplicateReferenceRaisesAPaymentIdReusedAlert() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "DUPLICATE_REFERENCE", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_ID_REUSED");
    assertThat(statusOf(new StaffAlertKey(order, StaffAlertKind.PAYMENT_ID_REUSED)))
        .isEqualTo("PENDING");
  }

  @Test
  void anAmountMismatchRaisesAPaymentAmountMismatchAlert() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "AMOUNT_MISMATCH", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_AMOUNT_MISMATCH");
    assertThat(statusOf(amountMismatch(order))).isEqualTo("PENDING");
  }

  /** An expired order still holds the captured money. */
  @Test
  void anExpiredOrderWithARecordingFailureIsAlerted() {
    UUID agreementId = createAgreement();
    UUID order = PaymentOrders.insert(jdbc, agreementId, "EXPIRED", null);
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
  }

  @Test
  void theSameFailureFromSeveralRoutesRaisesOneAlertHoweverOftenTheSweepRuns() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    String providerOrderId = PaymentOrders.providerOrderId(order);
    Instant anHourAgo = now.minus(Duration.ofHours(1));
    PaymentEvents.insert(
        jdbc, providerOrderId, "WEBHOOK", "RECORDING_FAILED", anHourAgo, anHourAgo);
    PaymentEvents.insert(
        jdbc, providerOrderId, "RECONCILIATION", "RECORDING_FAILED", anHourAgo, anHourAgo);

    dispatcher.enqueue(now);
    dispatcher.enqueue(now.plusSeconds(30));

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
  }

  @Test
  void twoKindsOfFailureOnOneOrderRaiseTwoAlerts() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "AMOUNT_MISMATCH", now.minus(Duration.ofHours(2)));
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_AMOUNT_MISMATCH", "PAYMENT_NOT_RECORDED");
  }

  @Test
  void anOrderRecordedAsPaidBeforeTheSweepRaisesNoUnrecordedPaymentAlert() {
    UUID agreementId = createAgreement();
    UUID order = PaymentOrders.insert(jdbc, agreementId, "PAID", now.minusSeconds(60));
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).isEmpty();
    // The paid order still raises its own alert, keyed by the agreement.
    assertThat(kindsFor(agreementId)).containsExactly("ORDER_PAID");
  }

  @Test
  void attemptsThatEndedAnotherWayRaiseNoAlert() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    Instant anHourAgo = now.minus(Duration.ofHours(1));
    ledgerRow(order, "ERRORED", anHourAgo);
    ledgerRow(order, "MISSING_REFERENCE", anHourAgo);
    ledgerRow(order, "ALREADY_CONFIRMED", anHourAgo);

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(anyKindAlertCount(agreementId)).isZero();
  }

  /**
   * A gateway event without payment detail is recorded as an amount mismatch at amount 0, because
   * the confirmation checks the amount before the missing payment id. It carries nothing to act on.
   */
  @Test
  void anAttemptThatCarriedNoPaymentIdRaisesNoAlert() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    Instant anHourAgo = now.minus(Duration.ofHours(1));
    PaymentEvents.insert(
        jdbc,
        PaymentOrders.providerOrderId(order),
        null,
        0,
        "WEBHOOK",
        "AMOUNT_MISMATCH",
        anHourAgo,
        anHourAgo);

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(anyKindAlertCount(agreementId)).isZero();
  }

  @Test
  void aFailureLastReceivedBeforeTheLookBackWindowRaisesNoAlert() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(25)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(anyKindAlertCount(agreementId)).isZero();
  }

  /** The window is measured on the last time the attempt was received, not the first. */
  @Test
  void aFailureFirstReceivedLongAgoAndStillRecurringIsAlerted() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    PaymentEvents.insert(
        jdbc,
        PaymentOrders.providerOrderId(order),
        "RECONCILIATION",
        "RECORDING_FAILED",
        now.minus(Duration.ofDays(3)),
        now.minus(Duration.ofHours(1)));

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
  }

  /**
   * Nothing to scope to: the attempt names no order of ours. So absorb what other tests left, then
   * prove this one row adds nothing.
   */
  @Test
  void anAttemptForAnOrderTheSystemDoesNotHoldRaisesNoAlert() {
    dispatcher.enqueue(now);
    int before = jdbc.queryForObject("SELECT count(*) FROM staff_alert", Integer.class);
    Instant anHourAgo = now.minus(Duration.ofHours(1));
    PaymentEvents.insert(
        jdbc,
        "order_unknown" + UUID.randomUUID(),
        "WEBHOOK",
        "RECORDING_FAILED",
        anHourAgo,
        anHourAgo);

    dispatcher.enqueue(now);

    assertTheLedgerLookBackRan();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Integer.class))
        .isEqualTo(before);
  }

  @Test
  void aDuePaymentNotRecordedAlertIsPostedOnceAndARecurrenceRaisesNothingMore() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    String paymentId = ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));
    dispatcher.enqueue(now);
    channelAnswers(204);

    dispatcher.dispatchOne(notRecorded(order), now);

    assertThat(statusOf(notRecorded(order))).isEqualTo("SENT");
    assertThat(attemptsOf(notRecorded(order))).isEqualTo(1);
    List<LoggedRequest> requests = requestsFor(agreementId, NOT_RECORDED_LEAD);
    assertThat(requests).hasSize(1);
    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(requests.get(0).getBodyAsString())
        .contains(trackingReferenceOf(agreementId))
        .contains("(TG)")
        .contains(SITE)
        .doesNotContain(agreementId.toString())
        .doesNotContain(order.toString())
        .doesNotContain(PaymentOrders.providerOrderId(order))
        .doesNotContain(paymentId)
        .doesNotContain(PARTY_FIRST_NAME)
        .doesNotContain(CITY);

    // The failure is received again: the ledger row's count rises, the alert does not repeat.
    PaymentEvents.receiveAgain(jdbc, PaymentOrders.providerOrderId(order), Instant.now());
    dispatcher.dispatchDue();

    assertTheLedgerLookBackRan();
    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
    assertThat(attemptsOf(notRecorded(order))).isEqualTo(1);
  }

  /** The collision the key was changed for: the order id is the subject of both alerts. */
  @Test
  void anOrderThatFailedRecordingAndIsLaterSurplusHoldsBothAlerts() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));
    dispatcher.enqueue(now);
    channelAnswers(204);
    dispatcher.dispatchOne(notRecorded(order), now);
    Map<String, Object> sent = alert(notRecorded(order));
    assertThat(sent.get("status")).isEqualTo("SENT");

    jdbc.update(
        "UPDATE payment_order SET status = 'PAID', surplus = TRUE, confirmed_at = ? WHERE id = ?",
        Timestamp.from(now.minusSeconds(10)),
        order);
    dispatcher.enqueue(now.plusSeconds(30));

    assertThat(kindsFor(order)).containsExactly("DUPLICATE_PAYMENT", "PAYMENT_NOT_RECORDED");
    assertThat(statusOf(duplicate(order))).isEqualTo("PENDING");
    assertThat(alert(notRecorded(order))).isEqualTo(sent);
  }

  @Test
  void settlingOneAlertOfAnOrderLeavesItsOtherAlertAlone() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    ledgerRow(order, "RECORDING_FAILED", now.minus(Duration.ofHours(1)));
    ledgerRow(order, "AMOUNT_MISMATCH", now.minus(Duration.ofHours(1)));
    dispatcher.enqueue(now);
    channelAnswers(204);
    CHANNEL.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(containing(MISMATCH_LEAD))
            .atPriority(1)
            .willReturn(aResponse().withStatus(404)));

    dispatcher.dispatchOne(notRecorded(order), now);
    dispatcher.dispatchOne(amountMismatch(order), now);

    assertThat(statusOf(notRecorded(order))).isEqualTo("SENT");
    assertThat(statusOf(amountMismatch(order))).isEqualTo("FAILED");
    assertThat(requestsFor(agreementId, NOT_RECORDED_LEAD)).hasSize(1);
    assertThat(requestsFor(agreementId, MISMATCH_LEAD)).hasSize(1);
  }

  /**
   * End to end: the gateway's own webhook reports a captured payment the database refuses to
   * record. Confirming it writes no alert row and contacts nobody - the ledger row is all that is
   * written - and the next sweep then posts exactly one payment-not-recorded alert.
   */
  @Test
  void aRecordingFailureReportedByTheWebhookIsAlertedByTheNextSweepAndNotBefore() {
    UUID agreementId = createAgreement();
    UUID order = outstandingOrder(agreementId);
    channelAnswers(204);

    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""
            + PaymentOrders.overLongPaymentId()
            + "\",\"order_id\":\""
            + PaymentOrders.providerOrderId(order)
            + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Razorpay-Signature", hmacSha256Hex(body, GATEWAY_WEBHOOK_KEY));
    ResponseEntity<String> answer =
        rest.postForEntity("/api/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(jdbc.queryForMap("SELECT status FROM payment_order WHERE id = ?", order))
        .containsEntry("status", "CREATED");
    // While confirming: no alert row for the order, and no request to the channel about it.
    assertThat(anyKindAlertCount(agreementId)).isZero();
    assertThat(requestsFor(agreementId)).isEmpty();

    Instant sweep = Instant.now().truncatedTo(ChronoUnit.MICROS);
    dispatcher.enqueue(sweep);
    dispatcher.dispatchOne(notRecorded(order), sweep);

    assertThat(kindsFor(order)).containsExactly("PAYMENT_NOT_RECORDED");
    assertThat(statusOf(notRecorded(order))).isEqualTo("SENT");
    assertThat(requestsFor(agreementId, NOT_RECORDED_LEAD)).hasSize(1);
    assertThat(requestsFor(agreementId)).hasSize(1);
  }

  /**
   * End to end: the gateway's own webhook confirms a second payment. Confirming it writes no alert
   * row and contacts nobody - the surplus mark on the order is all that is written - and the next
   * sweep then posts exactly one duplicate-payment alert.
   */
  @Test
  void aSurplusPaymentConfirmedByTheWebhookIsAlertedByTheNextSweepAndNotBefore() {
    UUID agreementId = paidAndEnqueued();
    UUID lateOrder = PaymentOrders.insert(jdbc, agreementId, "EXPIRED", null);
    Payments.markPaid(jdbc, agreementId, "pay_FIRST" + UUID.randomUUID());
    channelAnswers(204);

    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_LATE"
            + UUID.randomUUID()
            + "\",\"order_id\":\""
            + PaymentOrders.providerOrderId(lateOrder)
            + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Razorpay-Signature", hmacSha256Hex(body, GATEWAY_WEBHOOK_KEY));
    ResponseEntity<String> ack =
        rest.postForEntity("/api/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

    assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            jdbc.queryForMap("SELECT status, surplus FROM payment_order WHERE id = ?", lateOrder))
        .containsEntry("status", "PAID")
        .containsEntry("surplus", true);
    // While confirming: no alert row for the surplus order, and no request to the channel.
    assertThat(duplicateAlertCount(agreementId)).isZero();
    CHANNEL.verify(0, postRequestedFor(urlPathEqualTo(HOOK_PATH)));

    Instant sweep = Instant.now().truncatedTo(ChronoUnit.MICROS);
    dispatcher.enqueue(sweep);
    dispatcher.dispatchOne(duplicate(lateOrder), sweep);

    assertThat(duplicateAlertCount(agreementId)).isEqualTo(1);
    assertThat(statusOf(duplicate(lateOrder))).isEqualTo("SENT");
    assertThat(requestsFor(agreementId, DUPLICATE_LEAD)).hasSize(1);
    assertThat(requestsFor(agreementId, PAID_LEAD)).isEmpty();
  }

  private static String hmacSha256Hex(String body, String key) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}

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
import org.springframework.http.HttpStatus;
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
 * order becomes paid is the Razorpay tests' subject, and this change does not touch that path.
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

  private int alertCount(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM staff_alert WHERE agreement_id = ?", Integer.class, agreementId);
  }

  private Map<String, Object> alert(UUID agreementId) {
    return jdbc.queryForMap("SELECT * FROM staff_alert WHERE agreement_id = ?", agreementId);
  }

  private String statusOf(UUID agreementId) {
    return (String) alert(agreementId).get("status");
  }

  private int attemptsOf(UUID agreementId) {
    return ((Number) alert(agreementId).get("attempts")).intValue();
  }

  private Instant nextAttemptOf(UUID agreementId) {
    return ((Timestamp) alert(agreementId).get("next_attempt_at")).toInstant();
  }

  private String trackingReferenceOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
  }

  /** Requests the channel received that name this agreement's tracking reference. */
  private List<LoggedRequest> requestsFor(UUID agreementId) {
    return CHANNEL.findAll(
        postRequestedFor(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(containing(trackingReferenceOf(agreementId))));
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
    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(attemptsOf(agreementId)).isZero();
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
    PaymentOrders.insert(jdbc, agreementId, "FAILED", null);
    PaymentOrders.insert(jdbc, agreementId, "EXPIRED", null);
    PaymentOrders.insert(jdbc, agreementId, "CREATED", null);

    dispatcher.enqueue(now);

    assertThat(alertCount(agreementId)).isZero();
  }

  @Test
  void anAgreementWithNoGatewayOrderRaisesNoAlertWhateverItsPaymentState() {
    UUID unpaid = createAgreement();
    UUID staffConfirmed = createAgreement();
    Payments.markPaid(jdbc, staffConfirmed, "NEFT-" + UUID.randomUUID());
    UUID waived = createAgreement();
    Payments.waive(jdbc, waived);

    dispatcher.enqueue(now);

    assertThat(alertCount(unpaid)).isZero();
    assertThat(alertCount(staffConfirmed)).isZero();
    assertThat(alertCount(waived)).isZero();
  }

  // --- dispatch (6.4) ----------------------------------------------------------

  @Test
  void aDueAlertIsPostedOnceAndMarkedSentWithNothingPersonalInIt() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(204);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("SENT");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(alert(agreementId).get("sent_at")).isNotNull();
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
    dispatcher.dispatchOne(agreementId, now.plusSeconds(60));
    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(alertCount(agreementId)).isEqualTo(1);
  }

  @Test
  void aServerErrorIsRetriedAfterTheBackoffAndThenSent() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(nextAttemptOf(agreementId)).isAfter(now);

    // Not due yet: nothing happens inside the backoff.
    channelAnswers(204);
    dispatcher.dispatchOne(agreementId, now.plusSeconds(10));
    assertThat(requestsFor(agreementId)).hasSize(1);

    dispatcher.dispatchOne(agreementId, now.plus(StaffAlertBackoff.after(1)).plusSeconds(1));

    assertThat(statusOf(agreementId)).isEqualTo("SENT");
    assertThat(attemptsOf(agreementId)).isEqualTo(2);
    assertThat(requestsFor(agreementId)).hasSize(2);
  }

  @Test
  void aRateLimitedAlertStaysPendingForLater() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(429);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(nextAttemptOf(agreementId)).isAfter(now);
  }

  @Test
  void aNotFoundFailsTheAlertAfterOneAttempt() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(404);

    dispatcher.dispatchOne(agreementId, now);
    dispatcher.dispatchOne(agreementId, now.plus(Duration.ofHours(2)));

    assertThat(statusOf(agreementId)).isEqualTo("FAILED");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
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

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("FAILED");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    CHANNEL.verify(0, postRequestedFor(urlPathEqualTo("/elsewhere")));
  }

  @Test
  void aTransientFailureOnTheLastAttemptFailsTheAlert() {
    UUID agreementId = paidAndEnqueued();
    jdbc.update(
        "UPDATE staff_alert SET attempts = ? WHERE agreement_id = ?",
        StaffAlertBackoff.MAX_ATTEMPTS - 1,
        agreementId);
    channelAnswers(500);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("FAILED");
    assertThat(attemptsOf(agreementId)).isEqualTo(StaffAlertBackoff.MAX_ATTEMPTS);
    assertThat(requestsFor(agreementId)).hasSize(1);
  }

  @Test
  void anAlertLeftPendingAfterItsLastAttemptIsFailedWithoutAnotherSend() {
    UUID agreementId = paidAndEnqueued();
    jdbc.update(
        "UPDATE staff_alert SET attempts = ? WHERE agreement_id = ?",
        StaffAlertBackoff.MAX_ATTEMPTS,
        agreementId);
    channelAnswers(204);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("FAILED");
    assertThat(attemptsOf(agreementId)).isEqualTo(StaffAlertBackoff.MAX_ATTEMPTS);
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  @Test
  void anAlertPendingForMoreThanADayIsFailedWithoutBeingSent() {
    UUID agreementId = paidAndEnqueued();
    Instant stale = now.minus(Duration.ofHours(25));
    jdbc.update(
        "UPDATE staff_alert SET created_at = ?, next_attempt_at = ? WHERE agreement_id = ?",
        Timestamp.from(stale),
        Timestamp.from(stale),
        agreementId);
    channelAnswers(204);

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("FAILED");
    assertThat(attemptsOf(agreementId)).isZero();
    assertThat(requestsFor(agreementId)).isEmpty();
  }

  // --- the claim (6.5) ---------------------------------------------------------

  @Test
  void aRowIsClaimedOnceForTheAttemptCountTheCallerSaw() {
    UUID agreementId = paidAndEnqueued();
    Instant next = now.plus(StaffAlertBackoff.after(1));

    assertThat(persistence.claim(agreementId, 0, now, next)).isTrue();
    assertThat(persistence.claim(agreementId, 0, now, next)).isFalse();
    // Even once the lease has lapsed, a caller holding the old attempt count cannot claim.
    assertThat(persistence.claim(agreementId, 0, next.plusSeconds(1), next.plusSeconds(90)))
        .isFalse();

    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(nextAttemptOf(agreementId)).isEqualTo(next);
  }

  @Test
  void aClaimedRowCannotBeClaimedAgainUntilItsLeaseLapses() {
    UUID agreementId = paidAndEnqueued();
    Instant lease = now.plus(StaffAlertBackoff.after(1));
    assertThat(persistence.claim(agreementId, 0, now, lease)).isTrue();

    // The right attempt count, but the lease still holds: the SQL guard refuses, not the caller.
    assertThat(persistence.claim(agreementId, 1, now.plusSeconds(10), lease.plusSeconds(60)))
        .isFalse();
    assertThat(attemptsOf(agreementId)).isEqualTo(1);

    assertThat(persistence.claim(agreementId, 1, lease.plusSeconds(1), lease.plusSeconds(60)))
        .isTrue();
    assertThat(attemptsOf(agreementId)).isEqualTo(2);
  }

  @Test
  void twoDispatchesAtTheSameInstantPostOnce() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500); // stays PENDING, so only the lease the first claim wrote stops the second

    dispatcher.dispatchOne(agreementId, now);
    dispatcher.dispatchOne(agreementId, now);

    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
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
        "UPDATE staff_alert SET attempts = ? WHERE agreement_id = ?",
        StaffAlertBackoff.MAX_ATTEMPTS - 1,
        agreementId);
    channelAnswers(204);
    doThrow(new IllegalStateException("database unavailable"))
        .when(persistence)
        .markSent(any(), org.mockito.ArgumentMatchers.anyInt(), any());

    assertThatThrownBy(() -> dispatcher.dispatchOne(agreementId, now))
        .isInstanceOf(IllegalStateException.class);

    assertThat(requestsFor(agreementId)).hasSize(1);
    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }

  // --- nothing secret is logged (6.6) ------------------------------------------

  @Test
  void aServerErrorLogsNeitherTheWebhookUrlNorTheAgreementId() {
    UUID agreementId = paidAndEnqueued();
    channelAnswers(500);

    dispatcher.dispatchOne(agreementId, now);

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

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(logs.messages()).anyMatch(m -> m.contains("Staff alert send failed"));
    assertNothingSecretWasLogged(agreementId);
  }

  @Test
  void anUnexpectedErrorCarryingTheUrlIsRetriedAndNeverLogged() {
    UUID agreementId = paidAndEnqueued();
    doThrow(new IllegalStateException("I/O error on POST request for \"" + hookUrl() + "\""))
        .when(notifier)
        .send(any());

    dispatcher.dispatchOne(agreementId, now);

    assertThat(statusOf(agreementId)).isEqualTo("PENDING");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(logs.messages()).anyMatch(m -> m.contains("IllegalStateException"));
    assertNothingSecretWasLogged(agreementId);
  }

  // --- one full sweep (6.7) ----------------------------------------------------

  @Test
  void aFullSweepRecordsAndSendsAFreshPaidOrder() {
    // Settle everything earlier tests left behind, so the sweep's batch is this test's own row.
    jdbc.update(
        "INSERT INTO staff_alert (agreement_id, status, attempts, next_attempt_at, created_at,"
            + " sent_at) SELECT DISTINCT agreement_id, 'SENT', 1, now(), now(), now()"
            + " FROM payment_order WHERE status = 'PAID' ON CONFLICT DO NOTHING");
    jdbc.update("UPDATE staff_alert SET status = 'SENT', sent_at = now() WHERE status = 'PENDING'");
    UUID agreementId = createAgreement();
    PaymentOrders.insert(jdbc, agreementId, "PAID", Instant.now().minusSeconds(5));
    channelAnswers(204);

    dispatcher.dispatchDue();

    assertThat(statusOf(agreementId)).isEqualTo("SENT");
    assertThat(attemptsOf(agreementId)).isEqualTo(1);
    assertThat(requestsFor(agreementId)).hasSize(1);
    CHANNEL.verify(1, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }
}

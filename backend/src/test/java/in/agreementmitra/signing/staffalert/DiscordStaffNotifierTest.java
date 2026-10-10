package in.agreementmitra.signing.staffalert;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.signing.staffalert.StaffAlertDeliveryException.Kind;
import in.agreementmitra.support.LogCapture;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Discord adapter against a stub, with sub-second timeouts: how each response is classified,
 * what the request carries, and that the webhook URL - whose path holds the token - reaches neither
 * a log line nor an exception.
 */
class DiscordStaffNotifierTest {

  private static final String TOKEN = "tok-9f3aSECRETc41d";
  private static final String HOOK_PATH = "/api/webhooks/1/" + TOKEN;
  private static final StaffAlertMessage MESSAGE =
      new StaffAlertMessage(
          StaffAlertKind.ORDER_PAID, "AM7K2P9Q", "TG", URI.create("https://app.example.test"));

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  private final WireMockServer channel = new WireMockServer(options().dynamicPort());

  @BeforeEach
  void startChannel() {
    channel.start();
  }

  @AfterEach
  void stopChannel() {
    channel.stop();
  }

  private String hookUrl() {
    return channel.baseUrl() + HOOK_PATH;
  }

  private DiscordStaffNotifier notifier(String url) {
    return new DiscordStaffNotifier(url, Duration.ofSeconds(1), Duration.ofMillis(300));
  }

  private void channelAnswers(int status) {
    channel.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(status)));
  }

  private StaffAlertDeliveryException sendFails(DiscordStaffNotifier notifier) {
    StaffAlertDeliveryException thrown =
        catchThrowableOfType(StaffAlertDeliveryException.class, () -> notifier.send(MESSAGE));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getCause()).isNull();
    return thrown;
  }

  private void assertTheUrlIsNowhere(String url, Throwable thrown) {
    assertThat(logs.messages()).noneMatch(m -> m.contains(url) || m.contains(TOKEN));
    assertThat(logs.throwableMessages()).noneMatch(m -> m.contains(url) || m.contains(TOKEN));
    assertThat(String.valueOf(thrown.getMessage())).doesNotContain(TOKEN);
  }

  // --- classification ---------------------------------------------------------

  @Test
  void anAcceptedMessageIsSentWithMentionsAndPreviewsSuppressed() {
    channelAnswers(204);

    assertThatCode(() -> notifier(hookUrl()).send(MESSAGE)).doesNotThrowAnyException();

    channel.verify(
        1,
        postRequestedFor(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(
                equalToJson("{\"allowed_mentions\":{\"parse\":[]},\"flags\":4}", true, true)));
    assertThat(channel.getAllServeEvents().get(0).getRequest().getBodyAsString())
        .contains("AM7K2P9Q")
        .contains("TG");
  }

  @Test
  void aDuplicatePaymentAlertIsPostedWithItsOwnLeadAndTheSameSuppression() {
    channelAnswers(204);
    StaffAlertMessage duplicate =
        new StaffAlertMessage(
            StaffAlertKind.DUPLICATE_PAYMENT,
            "AM7K2P9Q",
            "TG",
            URI.create("https://app.example.test"));

    assertThatCode(() -> notifier(hookUrl()).send(duplicate)).doesNotThrowAnyException();

    channel.verify(
        1,
        postRequestedFor(urlPathEqualTo(HOOK_PATH))
            .withRequestBody(
                equalToJson("{\"allowed_mentions\":{\"parse\":[]},\"flags\":4}", true, true)));
    assertThat(channel.getAllServeEvents().get(0).getRequest().getBodyAsString())
        .contains("Possible duplicate payment - check before refunding: **AM7K2P9Q** (TG)")
        .doesNotContain("waiting for a stamp");
  }

  @Test
  void aPaidOrderAlertKeepsItsLead() {
    assertThat(DiscordStaffNotifier.content(MESSAGE))
        .isEqualTo("Paid order waiting for a stamp: **AM7K2P9Q** (TG)\nhttps://app.example.test");
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503})
  void aRateLimitOrServerErrorIsTransient(int status) {
    channelAnswers(status);

    StaffAlertDeliveryException thrown = sendFails(notifier(hookUrl()));

    assertThat(thrown.kind()).isEqualTo(Kind.TRANSIENT);
    assertThat(thrown.status()).isEqualTo(status);
    assertTheUrlIsNowhere(hookUrl(), thrown);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404})
  void aClientErrorIsPermanent(int status) {
    channelAnswers(status);

    StaffAlertDeliveryException thrown = sendFails(notifier(hookUrl()));

    assertThat(thrown.kind()).isEqualTo(Kind.PERMANENT);
    assertThat(thrown.status()).isEqualTo(status);
    assertTheUrlIsNowhere(hookUrl(), thrown);
  }

  @Test
  void aRedirectIsPermanentAndIsNotFollowed() {
    channel.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
    channel.stubFor(post(urlPathEqualTo("/elsewhere")).willReturn(aResponse().withStatus(204)));

    StaffAlertDeliveryException thrown = sendFails(notifier(hookUrl()));

    assertThat(thrown.kind()).isEqualTo(Kind.PERMANENT);
    assertThat(thrown.status()).isEqualTo(302);
    channel.verify(0, anyRequestedFor(urlPathEqualTo("/elsewhere")));
  }

  @Test
  void aHungChannelIsAbandonedAtTheReadTimeoutAsTransient() {
    channel.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .willReturn(aResponse().withStatus(204).withFixedDelay(2_000)));

    long started = System.nanoTime();
    StaffAlertDeliveryException thrown = sendFails(notifier(hookUrl()));

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(thrown.kind()).isEqualTo(Kind.TRANSIENT);
    assertThat(thrown.status()).isEqualTo(StaffAlertDeliveryException.NO_STATUS);
    assertTheUrlIsNowhere(hookUrl(), thrown);
  }

  @Test
  void aRefusedConnectionIsTransient() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    String url = "http://localhost:" + closedPort + HOOK_PATH;

    StaffAlertDeliveryException thrown = sendFails(notifier(url));

    assertThat(thrown.kind()).isEqualTo(Kind.TRANSIENT);
    assertTheUrlIsNowhere(url, thrown);
    // The failure is logged - by class, which is what the absence assertions above are tested on.
    assertThat(logs.messages()).anyMatch(m -> m.contains("Staff alert send failed"));
  }

  // --- configuration ----------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void aBlankUrlIsUnconfiguredAndSilent(String url) {
    assertThat(notifier(url).configured()).isFalse();
    assertThat(notifier(null).configured()).isFalse();
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ht tp://hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://127.evil.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://127.0.0.1.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "ftp://localhost/api/webhooks/1/tok-9f3aSECRETc41d",
        "https:///api/webhooks/1/tok-9f3aSECRETc41d"
      })
  void anUnusableUrlIsUnconfiguredWithOneErrorThatOmitsIt(String url) {
    DiscordStaffNotifier notifier = notifier(url);

    assertThat(notifier.configured()).isFalse();
    assertThat(logs.events()).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1);
    assertThat(logs.messages()).noneMatch(m -> m.contains(url) || m.contains(TOKEN));
    assertThat(logs.throwableMessages()).isEmpty();
    channel.verify(0, anyRequestedFor(anyUrl()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://localhost:9/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://127.0.0.1:9/api/webhooks/1/tok-9f3aSECRETc41d",
        "http://[::1]:9/api/webhooks/1/tok-9f3aSECRETc41d"
      })
  void anHttpsUrlOrALoopbackHttpUrlIsConfigured(String url) {
    assertThat(notifier(url).configured()).isTrue();
    assertThat(logs.hasLevel(Level.ERROR)).isFalse();
  }
}

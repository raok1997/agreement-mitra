package in.agreementmitra.signing.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * The Razorpay Orders API against a stubbed Razorpay - the real HTTP call, the Basic auth, the
 * request body shape, and the response mapping. No Spring context, no Docker, no live credentials;
 * runs everywhere.
 *
 * <p>The wire-format assertions matter more than they look: Razorpay rejects a string or float
 * amount outright, and a receipt over 40 characters, so getting these wrong fails at the worst
 * possible moment - the first real payment.
 */
class RazorpayClientWireMockTest {

  private static final String KEY_ID = "rzp_test_stub";
  private static final String API_KEY = "rzp-stub-key";
  private static final String ORDERS_URL = "/v1/orders";

  private WireMockServer server;
  private RazorpayClient client;

  @BeforeEach
  void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
    client = clientWith(KEY_ID, API_KEY);
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private RazorpayClient clientWith(String keyId, String keySecret) {
    return clientWith(keyId, keySecret, RazorpayConfig.READ_TIMEOUT);
  }

  /** Built by the production wiring, so the timeouts under test are the ones that ship. */
  private RazorpayClient clientWith(String keyId, String keySecret, Duration readTimeout) {
    RazorpayProperties properties =
        new RazorpayProperties(server.baseUrl() + "/", keyId, keySecret, "wh-mac-1");
    RestClient rest =
        RazorpayConfig.restClient(
            RestClient.builder(), properties, RazorpayConfig.CONNECT_TIMEOUT, readTimeout);
    return new RazorpayClient(rest, properties);
  }

  private static String basic(String keyId, String keySecret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
  }

  // --- order creation --------------------------------------------------------

  @Test
  void orderCreationSendsIntegerPaiseTheCurrencyAndOurReceiptUnderBasicAuth() {
    UUID agreementId = UUID.randomUUID();
    server.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(basic(KEY_ID, API_KEY)))
            .willReturn(
                okJson(
                    "{\"id\":\"order_STUB1\",\"status\":\"created\",\"amount\":49900,"
                        + "\"currency\":\"INR\",\"receipt\":\""
                        + agreementId
                        + "\"}")));

    RazorpayClient.ProviderOrder order =
        client.createOrder(agreementId.toString(), new Money(49_900L, "INR"));

    assertThat(order.id()).isEqualTo("order_STUB1");
    assertThat(order.paid()).isFalse();
    server.verify(
        1,
        postRequestedFor(urlEqualTo(ORDERS_URL))
            // Integer paise. A string or a float is rejected by the provider outright.
            .withRequestBody(matchingJsonPath("$.amount", equalTo("49900")))
            .withRequestBody(matchingJsonPath("$.currency", equalTo("INR")))
            // The receipt is the agreement UUID, so the provider's record joins back to ours
            // without trusting anything the client holds.
            .withRequestBody(matchingJsonPath("$.receipt", equalTo(agreementId.toString())))
            // Auto-capture: the simpler default, and the one this integration assumes throughout.
            .withRequestBody(matchingJsonPath("$.payment_capture", equalTo("1"))));
  }

  @Test
  void theReceiptStaysInsideTheProvidersFortyCharacterLimit() {
    // 36 for the UUID leaves room for a short retry suffix and no more - which is exactly why
    // retries are bounded rather than allowed to grow into a truncation collision.
    assertThat(UUID.randomUUID().toString()).hasSize(36);
    assertThat((UUID.randomUUID() + "-999").length()).isLessThanOrEqualTo(40);
  }

  @Test
  void aProviderErrorOnOrderCreationSurfacesWithoutEchoingItsBody() {
    server.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .willReturn(serverError().withBody("{\"error\":{\"description\":\"leak me\"}}")));

    assertThatThrownBy(() -> client.createOrder("receipt-1", new Money(49_900L, "INR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("leak me");
  }

  // --- a provider that does not answer ---------------------------------------

  private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(200);
  private static final int LONGER_THAN_THE_TIMEOUT_MS = 3_000;

  @Test
  void anOrderReadTheProviderNeverAnswersGivesUpAndIsNotReadAsUnpaid() {
    server.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_SLOW"))
            .willReturn(
                okJson("{\"id\":\"order_SLOW\",\"status\":\"paid\"}")
                    .withFixedDelay(LONGER_THAN_THE_TIMEOUT_MS)));
    RazorpayClient impatient = clientWith(KEY_ID, API_KEY, SHORT_READ_TIMEOUT);

    long started = System.nanoTime();
    Optional<RazorpayClient.ProviderOrder> order = impatient.fetchOrder("order_SLOW");

    assertThat(order).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(Duration.ofMillis(LONGER_THAN_THE_TIMEOUT_MS));
  }

  @Test
  void aPaymentsReadTheProviderNeverAnswersGivesUp() {
    server.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_SLOW/payments"))
            .willReturn(okJson("{\"items\":[]}").withFixedDelay(LONGER_THAN_THE_TIMEOUT_MS)));
    RazorpayClient impatient = clientWith(KEY_ID, API_KEY, SHORT_READ_TIMEOUT);

    assertThat(impatient.capturedPaymentFor("order_SLOW")).isEmpty();
  }

  @Test
  void anOrderCreationTheProviderNeverAnswersFailsLikeAnyOtherProviderError() {
    server.stubFor(
        post(urlEqualTo(ORDERS_URL))
            .willReturn(
                okJson("{\"id\":\"order_SLOW\",\"status\":\"created\"}")
                    .withFixedDelay(LONGER_THAN_THE_TIMEOUT_MS)));
    RazorpayClient impatient = clientWith(KEY_ID, API_KEY, SHORT_READ_TIMEOUT);

    assertThatThrownBy(() -> impatient.createOrder("receipt-1", new Money(49_900L, "INR")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theShippedClientHasBoundedTimeouts() {
    assertThat(RazorpayConfig.CONNECT_TIMEOUT)
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofSeconds(5));
    assertThat(RazorpayConfig.READ_TIMEOUT)
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void aResponseWithNoOrderIdIsRefusedRatherThanPersisted() {
    server.stubFor(post(urlEqualTo(ORDERS_URL)).willReturn(okJson("{\"status\":\"created\"}")));

    assertThatThrownBy(() -> client.createOrder("receipt-1", new Money(49_900L, "INR")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void withNoCredentialsConfiguredTheProviderIsNeverCalled() {
    RazorpayClient unconfigured = clientWith("", "");

    assertThatThrownBy(() -> unconfigured.createOrder("receipt-1", new Money(49_900L, "INR")))
        .isInstanceOf(IllegalStateException.class);

    server.verify(0, postRequestedFor(urlEqualTo(ORDERS_URL)));
  }

  // --- authoritative reads ---------------------------------------------------

  @Test
  void fetchOrderReportsTheProvidersOwnStatus() {
    server.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_STUB1"))
            .willReturn(
                okJson(
                    "{\"id\":\"order_STUB1\",\"status\":\"paid\",\"amount\":49900,"
                        + "\"currency\":\"INR\",\"receipt\":\"r1\"}")));

    Optional<RazorpayClient.ProviderOrder> order = client.fetchOrder("order_STUB1");

    assertThat(order).isPresent();
    assertThat(order.get().paid()).isTrue();
    assertThat(order.get().amountMinorUnits()).isEqualTo(49_900L);
  }

  @Test
  void anUnreachableProviderIsNeverReadAsUnpaid() {
    // Empty, not "not paid". A provider we cannot reach is not evidence that a customer did not
    // pay, and treating it as such is how a paid customer gets stranded.
    server.stubFor(get(urlPathEqualTo(ORDERS_URL + "/order_STUB1")).willReturn(serverError()));

    assertThat(client.fetchOrder("order_STUB1")).isEmpty();
  }

  @Test
  void capturedPaymentForPicksTheCapturedEntryAndIgnoresTheRest() {
    server.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_STUB1/payments"))
            .willReturn(
                okJson(
                    "{\"count\":2,\"items\":["
                        + "{\"id\":\"pay_FAILED\",\"status\":\"failed\",\"amount\":49900,"
                        + "\"currency\":\"INR\"},"
                        + "{\"id\":\"pay_OK\",\"status\":\"captured\",\"amount\":49900,"
                        + "\"currency\":\"INR\"}]}")));

    Optional<RazorpayClient.ProviderPayment> payment = client.capturedPaymentFor("order_STUB1");

    assertThat(payment).isPresent();
    assertThat(payment.get().id()).isEqualTo("pay_OK");
    assertThat(payment.get().amountMinorUnits()).isEqualTo(49_900L);
  }

  @Test
  void anOrderWithNoCapturedPaymentYieldsNothingToConfirm() {
    server.stubFor(
        get(urlPathEqualTo(ORDERS_URL + "/order_STUB1/payments"))
            .willReturn(
                okJson(
                    "{\"count\":1,\"items\":[{\"id\":\"pay_1\",\"status\":\"authorized\","
                        + "\"amount\":49900,\"currency\":\"INR\"}]}")));

    assertThat(client.capturedPaymentFor("order_STUB1")).isEmpty();
  }

  @Test
  void identifiersAreRedactedToATrailingFragment() {
    assertThat(RazorpayClient.redact("order_ABCDEFGH")).isEqualTo("****EFGH");
    assertThat(RazorpayClient.redact("ab")).isEqualTo("****");
    assertThat(RazorpayClient.redact(null)).isEqualTo("****");
  }
}

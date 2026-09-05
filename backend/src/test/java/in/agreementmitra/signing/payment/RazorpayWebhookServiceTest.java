package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Webhook intake: what verifies, what does not, and what happens afterwards.
 *
 * <p>No Spring context and no database - the confirmation path is mocked, because what is under
 * test here is the decision to call it at all, and with which values.
 */
class RazorpayWebhookServiceTest {

  private static final String WEBHOOK_KEY = "wh-mac-1";
  private static final String ORDER_ID = "order_RZP9f3k1";
  private static final String PAYMENT_ID = "pay_RZP8b2j0";

  private RazorpayClient razorpay;
  private PaymentOrderService orderService;
  private RazorpayWebhookService webhooks;

  @BeforeEach
  void setUp() {
    razorpay = mock(RazorpayClient.class);
    orderService = mock(PaymentOrderService.class);
    when(razorpay.webhookSecret()).thenReturn(WEBHOOK_KEY);
    webhooks = new RazorpayWebhookService(razorpay, orderService, new ObjectMapper());
  }

  private static String paymentCapturedBody(long amountMinorUnits) {
    return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
        + "\"id\":\""
        + PAYMENT_ID
        + "\",\"order_id\":\""
        + ORDER_ID
        + "\",\"amount\":"
        + amountMinorUnits
        + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
  }

  private static String orderPaidBody(long amountMinorUnits) {
    return "{\"event\":\"order.paid\",\"payload\":{"
        + "\"order\":{\"entity\":{\"id\":\""
        + ORDER_ID
        + "\",\"amount\":"
        + amountMinorUnits
        + ",\"currency\":\"INR\",\"status\":\"paid\"}},"
        + "\"payment\":{\"entity\":{\"id\":\""
        + PAYMENT_ID
        + "\",\"order_id\":\""
        + ORDER_ID
        + "\",\"amount\":"
        + amountMinorUnits
        + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
  }

  private String sign(String body) {
    return RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_KEY);
  }

  // --- verification ----------------------------------------------------------

  @Test
  void aVerifiedPaymentCapturedWebhookAppliesAConfirmation() {
    String body = paymentCapturedBody(49_900L);

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    verify(orderService).applyConfirmation(ORDER_ID, PAYMENT_ID, 49_900L, "INR");
  }

  @Test
  void aVerifiedOrderPaidWebhookAppliesTheSameConfirmation() {
    // The provider sends BOTH events for one successful payment. They must resolve to the same
    // order and the same payment id, so idempotency downstream records it exactly once.
    String body = orderPaidBody(49_900L);

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    verify(orderService).applyConfirmation(ORDER_ID, PAYMENT_ID, 49_900L, "INR");
  }

  @Test
  void anUnverifiedWebhookChangesNothingAtAll() {
    String body = paymentCapturedBody(49_900L);

    assertThat(webhooks.handle(body, "not-the-signature")).isFalse();
    assertThat(webhooks.handle(body, null)).isFalse();
    // Body altered after signing.
    assertThat(webhooks.handle(paymentCapturedBody(1L), sign(body))).isFalse();

    verifyNoInteractions(orderService);
  }

  @Test
  void anApiKeySignedWebhookIsRejected() {
    // Crossing the two credentials must fail closed, not silently work.
    String body = paymentCapturedBody(49_900L);

    assertThat(webhooks.handle(body, RazorpaySignatures.hmacSha256Hex(body, "rzp-key-1")))
        .isFalse();

    verifyNoInteractions(orderService);
  }

  @Test
  void withNoWebhookCredentialConfiguredEveryWebhookIsRejected() {
    when(razorpay.webhookSecret()).thenReturn("");
    String body = paymentCapturedBody(49_900L);

    assertThat(webhooks.handle(body, sign(body))).isFalse();

    verifyNoInteractions(orderService);
  }

  // --- what we act on --------------------------------------------------------

  @Test
  void anEventWeDoNotActOnIsAcknowledgedAndIgnored() {
    // Acknowledged so the provider stops retrying; ignored so the endpoint never grows a side
    // effect nobody designed. payment.failed in particular must not touch payment state.
    String body =
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{"
            + "\"id\":\""
            + PAYMENT_ID
            + "\",\"order_id\":\""
            + ORDER_ID
            + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"}}}}";

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    verify(orderService, never()).applyConfirmation(anyString(), anyString(), anyLong(), any());
  }

  @Test
  void aVerifiedButUnparseableBodyIsAcknowledgedWithoutActing() {
    String body = "not json at all";

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    verifyNoInteractions(orderService);
  }

  @Test
  void aVerifiedWebhookForAnUnknownOrderIsAcknowledgedIdenticallyToAKnownOne() {
    // No existence oracle: the caller cannot tell from the response whether we hold the order.
    when(orderService.applyConfirmation(anyString(), anyString(), anyLong(), any()))
        .thenReturn(ConfirmationOutcome.UNKNOWN_ORDER);
    String body = paymentCapturedBody(49_900L);

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    when(orderService.applyConfirmation(anyString(), anyString(), anyLong(), any()))
        .thenReturn(ConfirmationOutcome.CONFIRMED);
    assertThat(webhooks.handle(body, sign(body))).isTrue();
  }

  @Test
  void aNonIntegerAmountIsNotCoercedIntoAPlausibleAmount() {
    // A float on the wire is a malformed event, not a 499.00 payment. Passing 0 through fails the
    // downstream cross-check, which is the correct outcome - money never becomes floating point.
    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
            + "\"id\":\""
            + PAYMENT_ID
            + "\",\"order_id\":\""
            + ORDER_ID
            + "\",\"amount\":499.00,\"currency\":\"INR\"}}}}";

    assertThat(webhooks.handle(body, sign(body))).isTrue();

    verify(orderService).applyConfirmation(eq(ORDER_ID), eq(PAYMENT_ID), eq(0L), eq("INR"));
  }

  // --- redaction -------------------------------------------------------------

  @Test
  void neitherThePayloadNorTheIdentifiersNorTheCredentialReachTheLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(RazorpayWebhookService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String body = paymentCapturedBody(49_900L);
      webhooks.handle(body, sign(body)); // verified
      webhooks.handle(body, "forged"); // rejected

      String logged =
          appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);

      assertThat(logged).doesNotContain(ORDER_ID); // redacted to a trailing fragment
      assertThat(logged).doesNotContain(PAYMENT_ID);
      assertThat(logged).doesNotContain(WEBHOOK_KEY); // the credential, never
      assertThat(logged).doesNotContain("49900"); // no amounts
      assertThat(logged).doesNotContain("payload"); // never the body verbatim
    } finally {
      logger.detachAppender(appender);
    }
  }
}

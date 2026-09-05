package in.agreementmitra.signing.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Inbound Razorpay webhooks: verify, then apply.
 *
 * <p><b>Verification happens before anything is parsed.</b> The signature is HMAC-SHA256 over the
 * <b>raw request body</b> keyed by the <b>webhook secret</b>, so the body must reach the digest
 * byte-for-byte as received. Binding it to a DTO first and re-serialising would change key order,
 * whitespace, and number formatting, and the digest would then never match - the single most common
 * cause of "signature mismatch" in Razorpay integrations, and one that fails 100% of the time
 * rather than intermittently (design D3). The controller therefore takes a {@code String}, exactly
 * as the eSign webhook controller already does.
 *
 * <p><b>Two events describe one payment.</b> Razorpay sends {@code payment.captured} and {@code
 * order.paid} for the same successful payment, and redelivers both. Confirmation is idempotent, so
 * the payment is recorded exactly once whichever arrives first and however often either repeats.
 *
 * <p><b>No existence oracle.</b> A verified webhook naming an order we do not hold is acknowledged
 * indistinguishably from one we do. The body is never logged verbatim and never echoed in a
 * response; provider identifiers are redacted.
 *
 * <p>Java-{@code public} so the {@code api} controller can call it; still Modulith-internal.
 */
@Service
public class RazorpayWebhookService {

  private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

  /** A payment was captured. Carries the payment entity, including its {@code order_id}. */
  static final String EVENT_PAYMENT_CAPTURED = "payment.captured";

  /** An order was fully paid. Carries both the order entity and the payment entity. */
  static final String EVENT_ORDER_PAID = "order.paid";

  private final RazorpayClient razorpay;
  private final PaymentOrderService orderService;
  private final ObjectMapper objectMapper;

  RazorpayWebhookService(
      RazorpayClient razorpay, PaymentOrderService orderService, ObjectMapper objectMapper) {
    this.razorpay = razorpay;
    this.orderService = orderService;
    this.objectMapper = objectMapper;
  }

  /**
   * Verify and apply one webhook.
   *
   * @param rawBody the request body exactly as received - never re-serialised
   * @param presentedSignature the {@code X-Razorpay-Signature} header value
   * @return whether the request verified. {@code false} means the caller rejects it with no state
   *     change; {@code true} means it is acknowledged, whether or not it named anything we hold.
   */
  public boolean handle(String rawBody, String presentedSignature) {
    if (!RazorpaySignatures.webhookSignatureValid(
        rawBody, presentedSignature, razorpay.webhookSecret())) {
      // No body, no header value, no order id in the log - a rejected webhook tells us nothing
      // worth recording and everything worth leaking.
      log.warn("Payment webhook rejected: signature did not verify");
      return false;
    }
    apply(rawBody);
    return true;
  }

  /**
   * Parse the now-trusted body and apply any confirmation it describes. Every failure mode here -
   * unparseable, an event we do not act on, an order we do not hold - produces the same
   * acknowledgement, deliberately.
   */
  private void apply(String rawBody) {
    JsonNode root;
    try {
      root = objectMapper.readTree(rawBody);
    } catch (Exception malformed) {
      // Never echo the payload, not even in a debug line.
      log.warn("Payment webhook verified but could not be parsed");
      return;
    }
    String event = text(root, "event");
    if (event == null) {
      return;
    }
    JsonNode payload = root.path("payload");
    JsonNode payment = payload.path("payment").path("entity");
    String normalized = event.trim().toLowerCase(Locale.ROOT);

    String providerOrderId;
    if (EVENT_PAYMENT_CAPTURED.equals(normalized)) {
      providerOrderId = text(payment, "order_id");
    } else if (EVENT_ORDER_PAID.equals(normalized)) {
      providerOrderId = text(payload.path("order").path("entity"), "id");
    } else {
      // Every other event (payment.failed, refunds, settlements, ...) is acknowledged and ignored.
      // Acting on an event we have not specified is how a webhook endpoint grows a side effect
      // nobody designed.
      return;
    }
    if (providerOrderId == null) {
      return;
    }

    ConfirmationOutcome outcome =
        orderService.applyConfirmation(
            providerOrderId,
            text(payment, "id"),
            longValue(payment, "amount"),
            text(payment, "currency"));
    log.debug(
        "Payment webhook {} for order {} -> {}",
        normalized,
        RazorpayClient.redact(providerOrderId),
        outcome);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String asText = value.asText();
    return asText.isBlank() ? null : asText;
  }

  /**
   * A monetary field as an integer. Anything that is not an integral JSON number is 0, which fails
   * the amount cross-check rather than being coerced into a plausible amount - money never becomes
   * a floating-point value on the way in.
   */
  private static long longValue(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isIntegralNumber() ? value.asLong() : 0L;
  }
}

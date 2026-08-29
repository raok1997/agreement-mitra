package in.agreementmitra.signing.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The Razorpay Orders API, over plain {@link RestClient} plus the JDK's HMAC (design D10). We need
 * two endpoints and one digest; a vendor SDK would add supply-chain surface, need locking, and have
 * to clear the OSV gate on every build for no gain. Revisit if refunds, settlements, or
 * subscriptions ever land, where an SDK earns its place.
 *
 * <p>All vendor specifics live here: the paths, the Basic auth (wired in {@link RazorpayConfig}),
 * the wire shapes, and the {@code created -> attempted -> paid} status vocabulary.
 *
 * <p><b>Amounts are integers in paise on the wire.</b> Razorpay rejects strings and floats
 * outright, which is a mercy - it makes the money representation a compile-time concern rather than
 * a production surprise.
 *
 * <p><b>Logging.</b> Provider order and payment identifiers are redacted to a trailing fragment;
 * payloads are never logged verbatim; no credential appears at all.
 */
@Component
class RazorpayClient {

  private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

  private static final String ORDERS_PATH = "v1/orders";

  /** Razorpay's own terminal "this order has been paid for" status. */
  static final String ORDER_STATUS_PAID = "paid";

  /** Razorpay's terminal per-payment status under auto-capture. */
  static final String PAYMENT_STATUS_CAPTURED = "captured";

  private final RestClient client;
  private final RazorpayProperties properties;

  RazorpayClient(RestClient razorpayRestClient, RazorpayProperties properties) {
    this.client = razorpayRestClient;
    this.properties = properties;
  }

  /**
   * Place an order. The amount is the server's own calculation in paise, the currency is explicit,
   * and the receipt is our identifier for the agreement - so the provider's record joins back to
   * ours without relying on anything the client holds.
   *
   * @throws IllegalStateException when no credentials are configured (refuse, do not half-try) or
   *     the provider returns no order id
   */
  ProviderOrder createOrder(String receipt, Money amount) {
    requireConfigured();
    OrderResponse response;
    try {
      response =
          client
              .post()
              .uri(ORDERS_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new CreateOrderBody(amount.minorUnits(), amount.currency(), receipt, 1))
              .retrieve()
              .body(OrderResponse.class);
    } catch (RestClientException e) {
      // Never echo the provider's body: it can carry the receipt and the amount.
      throw new IllegalStateException("payment provider rejected the order request");
    }
    if (response == null || response.id() == null || response.id().isBlank()) {
      throw new IllegalStateException("payment provider returned no order id");
    }
    log.debug("Placed payment order {}", redact(response.id()));
    return response.toProviderOrder();
  }

  /**
   * Read an order's authoritative state from the provider. This is the second way a payment may be
   * confirmed (the first being a verified webhook) and the basis of reconciliation: a customer who
   * paid and closed the tab must not stay unpaid because a callback never ran.
   *
   * <p>Empty on any transport or decoding failure - a provider we cannot reach is not evidence that
   * an order does not exist, and must never be read as "unpaid".
   */
  Optional<ProviderOrder> fetchOrder(String providerOrderId) {
    requireConfigured();
    try {
      OrderResponse response =
          client
              .get()
              .uri(ORDERS_PATH + "/{orderId}", providerOrderId)
              .retrieve()
              .body(OrderResponse.class);
      return Optional.ofNullable(response).map(OrderResponse::toProviderOrder);
    } catch (RestClientException e) {
      log.warn("Could not read payment order {} from the provider", redact(providerOrderId));
      return Optional.empty();
    }
  }

  /**
   * The captured payment against an order, if there is one. Read only after the order itself
   * reports paid, so an unpaid or failed order can never be turned into a confirmation by this
   * call.
   */
  Optional<ProviderPayment> capturedPaymentFor(String providerOrderId) {
    requireConfigured();
    try {
      PaymentsResponse response =
          client
              .get()
              .uri(ORDERS_PATH + "/{orderId}/payments", providerOrderId)
              .retrieve()
              .body(PaymentsResponse.class);
      if (response == null || response.items() == null) {
        return Optional.empty();
      }
      return response.items().stream()
          .filter(item -> PAYMENT_STATUS_CAPTURED.equalsIgnoreCase(item.status()))
          .findFirst()
          .map(PaymentItem::toProviderPayment);
    } catch (RestClientException e) {
      log.warn("Could not read payments for order {} from the provider", redact(providerOrderId));
      return Optional.empty();
    }
  }

  /** The public key identifier the browser needs to open Checkout. Never a secret. */
  String publicKeyId() {
    return properties.keyId();
  }

  /** The key secret, for handler-signature verification only. Never leaves the server. */
  String keySecret() {
    return properties.keySecret();
  }

  /** The webhook secret, for webhook verification only. Never leaves the server. */
  String webhookSecret() {
    return properties.webhookSecret();
  }

  boolean apiConfigured() {
    return properties.apiConfigured();
  }

  private void requireConfigured() {
    if (!properties.apiConfigured()) {
      throw new IllegalStateException("payment provider is not configured");
    }
  }

  /** Redact an identifier for logs - the last 4 characters only, so a leak is not exploitable. */
  static String redact(String id) {
    if (id == null || id.length() <= 4) {
      return "****";
    }
    return "****" + id.substring(id.length() - 4);
  }

  // --- vendor-neutral results ------------------------------------------------

  /**
   * One provider order as we care about it.
   *
   * @param id the provider's order id
   * @param status the provider's own status ({@code created} / {@code attempted} / {@code paid})
   * @param amountMinorUnits the order amount in paise
   * @param currency ISO-4217 code
   * @param receipt our identifier, echoed back
   */
  record ProviderOrder(
      String id, String status, long amountMinorUnits, String currency, String receipt) {

    /** Whether the provider itself says this order has been paid for. */
    boolean paid() {
      return status != null && ORDER_STATUS_PAID.equalsIgnoreCase(status.trim());
    }
  }

  /** One captured payment: the id becomes the external reference on the confirmation seam. */
  record ProviderPayment(String id, String status, long amountMinorUnits, String currency) {}

  // --- wire shapes (private; never leak outside the adapter) -----------------
  //
  // Only the fields we act on are modelled. Nothing here binds a card, UPI, bank, or contact field
  // the provider may return: if they are never deserialised they can never be logged, echoed, or
  // accidentally persisted, and we stay out of PCI scope by construction.

  /**
   * @param payment_capture 1 = auto-capture. The simpler default and the one assumed throughout;
   *     manual capture would only matter if we wanted to authorise before stamping and capture
   *     after.
   */
  private record CreateOrderBody(
      long amount,
      String currency,
      String receipt,
      @JsonProperty("payment_capture") int paymentCapture) {}

  private record OrderResponse(
      String id, String status, Long amount, String currency, String receipt) {

    ProviderOrder toProviderOrder() {
      return new ProviderOrder(
          id,
          status == null ? null : status.toLowerCase(Locale.ROOT),
          amount == null ? 0L : amount,
          currency,
          receipt);
    }
  }

  private record PaymentsResponse(List<PaymentItem> items) {}

  private record PaymentItem(String id, String status, Long amount, String currency) {

    ProviderPayment toProviderPayment() {
      return new ProviderPayment(
          id,
          status == null ? null : status.toLowerCase(Locale.ROOT),
          amount == null ? 0L : amount,
          currency);
    }
  }
}

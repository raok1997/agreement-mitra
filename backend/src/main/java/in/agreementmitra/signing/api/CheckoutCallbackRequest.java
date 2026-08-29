package in.agreementmitra.signing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the browser reports back after the hosted checkout closes: the provider's order id, payment
 * id, and the handler signature over {@code order_id|payment_id}.
 *
 * <p><b>Every field here is client-supplied and is treated as a user-experience signal only.</b>
 * Verifying the signature proves the provider <em>issued</em> these values; it does not prove they
 * are current, and it certainly does not prove the payment reached us. Posting this NEVER marks an
 * agreement paid - it may advance the UI and it may trigger an authoritative read of the order, and
 * that read is what decides.
 *
 * <p>There is deliberately <b>no amount, currency, or discount field</b>: the amount is the
 * server's calculation and a client cannot influence what is charged or what is credited. Nor is
 * there any field that could carry a card, UPI, netbanking, or wallet credential - those are
 * collected entirely by the provider's hosted checkout and never reach our servers.
 *
 * <p>Lengths are bounded so a malformed post cannot become a memory-pressure lever.
 *
 * @param razorpayOrderId the provider's order id
 * @param razorpayPaymentId the provider's payment id
 * @param razorpaySignature HMAC-SHA256 of {@code order_id|payment_id} under the API key secret
 */
public record CheckoutCallbackRequest(
    @NotBlank @Size(max = 64) String razorpayOrderId,
    @NotBlank @Size(max = 64) String razorpayPaymentId,
    @NotBlank @Size(max = 256) String razorpaySignature) {}

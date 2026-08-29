package in.agreementmitra.signing.api;

import java.util.UUID;

/**
 * Everything the browser needs to open the gateway's hosted checkout - and nothing else.
 *
 * <p><b>No secret is here, and none can be.</b> {@code keyId} is the provider's <b>public</b>
 * identifier, published to the browser by design. The API key secret and the webhook secret are
 * server-only, are never returned by any endpoint, and appear nowhere in frontend code, config, or
 * build output.
 *
 * <p>The amount is the <b>server's</b> calculation in integer minor units. It is reported here for
 * display only: no request accepts an amount, currency, or discount, so what the browser is shown
 * is what was placed with the provider.
 *
 * <p>{@code orderStatus} and {@code paymentState} let the SPA settle correctly without guessing -
 * an order that is already paid (the webhook landed while the customer was away) is reported as
 * such rather than re-opening checkout.
 *
 * @param agreementId the agreement being paid for
 * @param keyId the provider's public key identifier
 * @param orderId the provider's order id
 * @param amountMinorUnits the payable amount in paise, as an integer
 * @param currency ISO-4217 code
 * @param orderStatus our order status: {@code CREATED} / {@code PAID} / {@code FAILED} / {@code
 *     EXPIRED}
 * @param paymentState the agreement's payment state: {@code UNPAID} / {@code PAID} / {@code WAIVED}
 */
public record CheckoutSessionResponse(
    UUID agreementId,
    String keyId,
    String orderId,
    long amountMinorUnits,
    String currency,
    String orderStatus,
    String paymentState) {}

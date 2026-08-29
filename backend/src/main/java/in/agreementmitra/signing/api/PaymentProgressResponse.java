package in.agreementmitra.signing.api;

import java.util.UUID;

/**
 * The customer-facing view of where a payment stands: the agreement's payment state plus the latest
 * order's status and amount.
 *
 * <p>This is what the SPA polls after the checkout modal closes. The modal closing proves nothing -
 * the browser may have been closed, the callback may never have run - so the UI settles on this,
 * which reflects only what a verified webhook or an authoritative provider read has established.
 *
 * <p>Carries no secret, no party PII, and no provider payment identifier: the customer does not
 * need the vendor's reference to know they have paid, and it is redacted everywhere else.
 *
 * @param agreementId the agreement
 * @param paymentState {@code UNPAID} / {@code PAID} / {@code WAIVED}
 * @param orderStatus the latest order's status, or null when payment was never started
 * @param amountMinorUnits the latest order's amount in paise, or null when there is no order
 * @param currency ISO-4217 code, or null when there is no order
 */
public record PaymentProgressResponse(
    UUID agreementId,
    String paymentState,
    String orderStatus,
    Long amountMinorUnits,
    String currency) {}

package in.agreementmitra.signing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An agreement's payment state as read or reported. {@code PAID} and {@code WAIVED} are surfaced as
 * distinct values and are never collapsed - one says money came in, the other says someone decided
 * to proceed without it, and a report has to be able to tell them apart.
 *
 * <p>Carries no party PII and no gateway credential: the external {@code reference} is a payment
 * identifier, and a waiver carries no amount at all.
 *
 * @param agreementId the agreement
 * @param paymentState one of {@code UNPAID} / {@code PAID} / {@code WAIVED}
 * @param amount amount received; null for {@code UNPAID} and for a waiver
 * @param currency ISO-4217 code; null unless an amount was recorded
 * @param reference the external payment reference; null for {@code UNPAID} and for a waiver
 * @param recordedAt when the state was last set; null while {@code UNPAID}
 */
public record PaymentStateResponse(
    UUID agreementId,
    String paymentState,
    BigDecimal amount,
    String currency,
    String reference,
    Instant recordedAt) {}

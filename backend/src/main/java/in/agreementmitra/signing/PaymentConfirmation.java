package in.agreementmitra.signing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The <b>vendor-neutral payment-confirmation seam</b>: everything the system records when it
 * accepts that an agreement has been paid for. A future payment gateway becomes one adapter that
 * produces this value; today the only producer is a STAFF-recorded manual confirmation.
 *
 * <p>Deliberately a superset of what any gateway confirmation provides (agreement, amount,
 * currency, external reference, time), so onboarding a gateway does not reshape the seam. A payment
 * taken out of band still has to be recordable, so the manual path stays available alongside any
 * future gateway-backed one.
 *
 * <p>{@code reference} is the external payment identifier and SHALL be unique where present - one
 * payment cannot be recorded against two agreements (enforced by a unique index, caught as a 409).
 *
 * <p>No card, UPI, or gateway credential ever passes through here; this change integrates no
 * gateway.
 *
 * @param agreementId the agreement the payment is for
 * @param amount the amount received
 * @param currency ISO-4217 code (e.g. {@code INR})
 * @param reference the external payment reference; unique where present
 * @param confirmedAt when the confirmation was recorded
 */
public record PaymentConfirmation(
    UUID agreementId, BigDecimal amount, String currency, String reference, Instant confirmedAt) {}

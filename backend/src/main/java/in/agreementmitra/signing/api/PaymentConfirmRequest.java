package in.agreementmitra.signing.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A STAFF-recorded manual payment confirmation. Deliberately carries <b>no payment state field</b>:
 * payment state is server-managed and is never settable by a client - the server sets {@code PAID}
 * because this endpoint was called, not because a body said so (anti-mass-assignment).
 *
 * @param amount the amount received; must be positive
 * @param currency ISO-4217 code; defaults to {@code INR} when omitted
 * @param reference the external payment reference, unique where present
 */
public record PaymentConfirmRequest(
    @NotNull @DecimalMin(value = "0.01", message = "must be a positive amount") BigDecimal amount,
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO-4217 code")
        String currency,
    @Size(max = 128) String reference) {}

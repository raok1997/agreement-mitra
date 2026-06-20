package in.agreementmitra.signing.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Create-agreement request body. Holds only client-supplied content — {@code id} and {@code
 * createdAt} are server-assigned by the aggregate and deliberately absent here (no mass-assignment
 * of server-owned fields). {@code @Size}/{@code @Max} bound the otherwise unbounded free-text and
 * term inputs of an unauthenticated endpoint; {@code @Digits} keeps money within the {@code
 * numeric(12,2)} column.
 */
public record CreateAgreementRequest(
    @NotBlank @Size(max = 200) String landlordName,
    @NotBlank @Size(max = 200) String tenantName,
    @NotBlank @Size(max = 500) String propertyAddress,
    @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
    @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
    @Positive @Max(1200) int termMonths) {}

package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for creating an agreement. Carries only client-settable fields — no id, no {@code
 * createdAt} (anti-mass-assignment: the server assigns those). Money digits are capped to the
 * stored {@code numeric(12,2)} scale so an over-precise amount is a clean 400, not a silent
 * truncation. The cross-field rules (≥1 owner && ≥1 tenant, unique emails) live in {@link
 * ValidSignerSet}.
 */
@ValidSignerSet
public record CreateAgreementRequest(
    @NotBlank String propertyAddress,
    @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
    @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
    @Positive int termMonths,
    @NotNull @Size(min = 1, max = 20) @Valid List<SignerRequest> signers) {

  /** One owner or tenant to invite. No id — assigned server-side. */
  public record SignerRequest(
      @NotBlank String name, @NotBlank @Email String email, @NotNull Role role) {}
}

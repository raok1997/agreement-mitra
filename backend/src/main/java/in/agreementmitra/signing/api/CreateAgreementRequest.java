package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request body for creating an agreement. Carries only client-settable fields — no id, no {@code
 * createdAt}, no {@code termMonths}, no {@code duration} (anti-mass-assignment: the server assigns
 * or derives those). Money digits are capped to the stored {@code numeric(12,2)} scale so an
 * over-precise amount is a clean 400. The tenancy is a {@code startDate}/{@code endDate} pair; the
 * term in months and the display duration are derived from them server-side.
 *
 * <p>Each party carries structured name parts + current address. Contact ({@code email}/{@code
 * mobile}) is optional at draft (enforced only before signing). {@code name} is an optional
 * full-name-as-per-Aadhaar override; when absent the server derives it from first + last name. The
 * cross-field rules live in {@link ValidSignerSet} (role mix + duplicate email) and {@link
 * EndAfterStart} (date ordering).
 *
 * <p>{@code state}/{@code type} are the OPTIONAL catalog-selection dimensions. When both are
 * supplied the server resolves the published template for {@code (state, type)} via {@code
 * documents.api}'s {@code TemplateCatalogApi} and records its id server-side (never a
 * client-settable template UUID); when absent (or only one supplied) the agreement keeps today's
 * default behaviour (no selection -> default effective template at render). They are plain
 * selection tokens, not user data.
 */
@ValidSignerSet
@EndAfterStart
public record CreateAgreementRequest(
    @NotBlank(message = "Property address is required.") String propertyAddress,
    @NotNull(message = "Monthly rent is required.")
        @Positive(message = "Monthly rent must be greater than 0.")
        @Digits(integer = 10, fraction = 2, message = "Monthly rent can have at most two decimals.")
        BigDecimal monthlyRent,
    @NotNull(message = "Security deposit is required.")
        @PositiveOrZero(message = "Security deposit cannot be negative.")
        @Digits(
            integer = 10,
            fraction = 2,
            message = "Security deposit can have at most two decimals.")
        BigDecimal securityDeposit,
    @NotNull(message = "Start date is required.") LocalDate startDate,
    @NotNull(message = "End date is required.") LocalDate endDate,
    @NotNull(message = "Add at least one owner and one tenant.")
        @Size(min = 1, max = 20, message = "An agreement can have between 1 and 20 people.")
        @Valid
        List<SignerRequest> signers,
    String state,
    String type) {

  /**
   * Backward-compatible constructor without catalog dimensions: an agreement created with no {@code
   * (state, type)} keeps today's default effective-template behaviour. Delegates to the canonical
   * constructor with null dimensions.
   */
  public CreateAgreementRequest(
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      LocalDate startDate,
      LocalDate endDate,
      List<SignerRequest> signers) {
    this(propertyAddress, monthlyRent, securityDeposit, startDate, endDate, signers, null, null);
  }

  /** One owner or tenant. No id — assigned server-side. */
  public record SignerRequest(
      @NotBlank(message = "First name is required.") String firstName,
      @NotBlank(message = "Last name is required.") String lastName,
      @NotBlank(message = "Father's name is required.") String fatherName,
      @NotBlank(message = "Current address is required.") String currentAddress,
      @Email(message = "Enter a valid email address.") String email,
      @Pattern(regexp = "\\+?[0-9]{6,15}", message = "Enter a valid mobile number.") String mobile,
      @Pattern(regexp = ".*\\S.*", message = "Full name must not be blank.") String name,
      @NotNull(message = "Each person must be an owner or a tenant.") Role role) {}
}

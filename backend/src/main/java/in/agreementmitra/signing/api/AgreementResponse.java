package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response view of a persisted agreement. Carries the tenancy dates and the server-derived {@code
 * durationMonths} (the whole-month term shown to the user), plus each party's structured fields and
 * stored full {@code name}. {@code createdAt} serializes as ISO-8601 and {@code startDate}/{@code
 * endDate} as ISO dates (Boot's default {@code JavaTimeModule}).
 */
public record AgreementResponse(
    UUID id,
    String propertyAddress,
    BigDecimal monthlyRent,
    BigDecimal securityDeposit,
    LocalDate startDate,
    LocalDate endDate,
    int durationMonths,
    Instant createdAt,
    List<SignerResponse> signers) {

  public record SignerResponse(
      UUID id,
      String name,
      String firstName,
      String lastName,
      String fatherName,
      String currentAddress,
      String email,
      String mobile,
      Role role) {}
}

package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response view of a persisted agreement, including each signer's server-assigned id. {@code
 * createdAt} serializes as ISO-8601 (Boot's default {@code JavaTimeModule}).
 */
public record AgreementResponse(
    UUID id,
    String propertyAddress,
    BigDecimal monthlyRent,
    BigDecimal securityDeposit,
    int termMonths,
    Instant createdAt,
    List<SignerResponse> signers) {

  public record SignerResponse(UUID id, String name, String email, Role role) {}
}

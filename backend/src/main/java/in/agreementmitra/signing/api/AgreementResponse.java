package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Agreement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Create/fetch response body: the server-assigned {@code id} and {@code createdAt} plus the six
 * stored content fields. {@code createdAt} is an instant, serialized ISO-8601 UTC ({@code …Z}).
 */
public record AgreementResponse(
    UUID id,
    String landlordName,
    String tenantName,
    String propertyAddress,
    BigDecimal monthlyRent,
    BigDecimal securityDeposit,
    int termMonths,
    Instant createdAt) {

  static AgreementResponse from(Agreement a) {
    return new AgreementResponse(
        a.getId(),
        a.getLandlordName(),
        a.getTenantName(),
        a.getPropertyAddress(),
        a.getMonthlyRent(),
        a.getSecurityDeposit(),
        a.getTermMonths(),
        a.getCreatedAt());
  }
}

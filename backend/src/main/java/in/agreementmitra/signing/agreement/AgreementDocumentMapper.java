package in.agreementmitra.signing.agreement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a persisted {@link Agreement} to the generic field-key data map the {@code documents}
 * document-projection consumes. Package-private -- it reads the aggregate's package-private
 * accessors and lives beside it. It emits only a plain {@link Map} of field key -> value (no entity
 * or PII type crosses to the {@code documents} module), keeping that module domain-agnostic: {@code
 * documents} receives the effective template's <b>declared field keys</b>, never a signing type.
 *
 * <p>The keys are the aggregate-backed field keys the production effective template declares as
 * required ({@code ownerName}, {@code tenantName}, {@code propertyAddress}, {@code monthlyRent},
 * {@code securityDeposit}, {@code durationMonths}, {@code startDate}, {@code endDate}); the first
 * owner/tenant supplies the single-party-per-role name fields (design D-C). Every OTHER field the
 * definition declares but the aggregate lacks (father's names, addresses, furnishing, charges,
 * etc.) is filled from the effective template's system-authored defaults inside the projection
 * service, not here -- so the signed draft (generate) stays in parity with the live preview. {@code
 * LocalDate} values are emitted as ISO strings, the operand form the projection's date
 * validator/coercer and {@code showWhen} evaluation expect. Multi-party rendering beyond the
 * single-party-per-role name fields is out of scope (a catalog/definition concern).
 */
final class AgreementDocumentMapper {

  private AgreementDocumentMapper() {}

  /** Build the document-projection data map (declared field keys) from the persisted aggregate. */
  static Map<String, Object> toTemplateData(Agreement agreement) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", firstNameByRole(agreement, Role.OWNER));
    data.put("tenantName", firstNameByRole(agreement, Role.TENANT));
    data.put("propertyAddress", agreement.propertyAddress());
    data.put("monthlyRent", agreement.monthlyRent());
    data.put("securityDeposit", agreement.securityDeposit());
    data.put("durationMonths", agreement.termMonths());
    data.put("startDate", agreement.startDate().toString());
    data.put("endDate", agreement.endDate().toString());
    return data;
  }

  /** The first signer's full name for {@code role}, or {@code null} if the aggregate has none. */
  private static String firstNameByRole(Agreement agreement, Role role) {
    for (Signer signer : agreement.signers()) {
      if (signer.role() == role) {
        return signer.name();
      }
    }
    return null;
  }
}

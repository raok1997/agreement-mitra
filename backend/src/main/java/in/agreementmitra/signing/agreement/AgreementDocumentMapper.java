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
 * <p>The keys are the twelve aggregate-backed field keys of the production effective template
 * ({@code ownerName}, {@code ownerFatherName}, {@code ownerAddress}, {@code tenantName}, {@code
 * tenantFatherName}, {@code tenantAddress}, {@code propertyAddress}, {@code monthlyRent}, {@code
 * securityDeposit}, {@code durationMonths}, {@code startDate}, {@code endDate}); every one except
 * the derived {@code durationMonths} is declared required. The first owner/tenant supplies the
 * party fields (design D-C), so the stored party record is authoritative for them. Values are
 * emitted raw -- a blank legacy party field stays blank and the projection's GENERATE validation
 * reports it; a role with no signer maps to {@code null}. Every OTHER declared field the aggregate
 * lacks (furnishing, charges, etc.) is filled from the effective template's system-authored
 * defaults inside the projection service, not here -- so the signed draft (generate) stays in
 * parity with the live preview. {@code LocalDate} values are emitted as ISO strings, the operand
 * form the projection's date validator/coercer and {@code showWhen} evaluation expect. Multi-party
 * rendering beyond the first party per role is out of scope (a catalog/definition concern).
 */
final class AgreementDocumentMapper {

  private AgreementDocumentMapper() {}

  /** Build the document-projection data map (declared field keys) from the persisted aggregate. */
  static Map<String, Object> toTemplateData(Agreement agreement) {
    Signer owner = firstSignerByRole(agreement, Role.OWNER);
    Signer tenant = firstSignerByRole(agreement, Role.TENANT);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", owner == null ? null : owner.name());
    data.put("ownerFatherName", owner == null ? null : owner.fatherName());
    data.put("ownerAddress", owner == null ? null : owner.currentAddress());
    data.put("tenantName", tenant == null ? null : tenant.name());
    data.put("tenantFatherName", tenant == null ? null : tenant.fatherName());
    data.put("tenantAddress", tenant == null ? null : tenant.currentAddress());
    data.put("propertyAddress", agreement.propertyAddress());
    data.put("monthlyRent", agreement.monthlyRent());
    data.put("securityDeposit", agreement.securityDeposit());
    data.put("durationMonths", agreement.termMonths());
    data.put("startDate", agreement.startDate().toString());
    data.put("endDate", agreement.endDate().toString());
    return data;
  }

  /** The first signer for {@code role}, or {@code null} if the aggregate has none. */
  private static Signer firstSignerByRole(Agreement agreement, Role role) {
    for (Signer signer : agreement.signers()) {
      if (signer.role() == role) {
        return signer;
      }
    }
    return null;
  }
}

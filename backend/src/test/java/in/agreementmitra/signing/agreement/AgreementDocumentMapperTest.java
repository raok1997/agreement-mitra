package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the agreement-to-document-projection mapping -- no Spring context, no render.
 * Proves the persisted aggregate is mapped to the effective template's aggregate-backed <b>declared
 * field keys</b> (first owner/tenant name, father's name and address, property address, rent,
 * deposit, duration, tenancy dates) and nothing signing-specific leaks into the data map.
 */
class AgreementDocumentMapperTest {

  private static Agreement sampleAgreement() {
    Agreement agreement =
        Agreement.create(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-01"));
    agreement.addSigner(
        "Asha Owner",
        "Asha",
        "Owner",
        "Ravi Owner",
        "1 First Street",
        "asha@example.com",
        null,
        Role.OWNER);
    agreement.addSigner(
        "Bhaskar Tenant",
        "Bhaskar",
        "Tenant",
        "Kiran Tenant",
        "2 Second Street",
        null,
        "9000000000",
        Role.TENANT);
    return agreement;
  }

  @Test
  void mapsDeclaredFieldKeysFromTheAggregate() {
    Map<String, Object> data = AgreementDocumentMapper.toTemplateData(sampleAgreement());

    // The production definition's twelve aggregate-backed keys: first owner/tenant name, father's
    // name and address, property address, rent, deposit, duration (termMonths), and the ISO
    // tenancy dates. Every other declared field is filled from the template's system-authored
    // defaults in the projection service, not here. Dates are emitted as ISO strings (the operand
    // form the date validator/coercer expects).
    assertThat(data)
        .containsOnlyKeys(
            "ownerName",
            "ownerFatherName",
            "ownerAddress",
            "tenantName",
            "tenantFatherName",
            "tenantAddress",
            "propertyAddress",
            "monthlyRent",
            "securityDeposit",
            "durationMonths",
            "startDate",
            "endDate")
        .containsEntry("ownerName", "Asha Owner")
        .containsEntry("ownerFatherName", "Ravi Owner")
        .containsEntry("ownerAddress", "1 First Street")
        .containsEntry("tenantName", "Bhaskar Tenant")
        .containsEntry("tenantFatherName", "Kiran Tenant")
        .containsEntry("tenantAddress", "2 Second Street")
        .containsEntry("propertyAddress", "12 MG Road, Bengaluru")
        .containsEntry("monthlyRent", new BigDecimal("25000.00"))
        .containsEntry("securityDeposit", new BigDecimal("50000.00"))
        .containsEntry("durationMonths", 11)
        .containsEntry("startDate", "2026-01-01")
        .containsEntry("endDate", "2026-12-01");
  }

  @Test
  void missingRoleMapsToNullName() {
    // An aggregate with only an owner: every tenant key is null (the projection's generate
    // validation is what enforces required-ness; the mapper just reflects what the aggregate
    // carries).
    Agreement ownerOnly =
        Agreement.create(
            "12 MG Road",
            new BigDecimal("10000.00"),
            new BigDecimal("20000.00"),
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-06-01"));
    ownerOnly.addSigner("Asha Owner", "Asha", "Owner", "Ravi", "1 St", null, null, Role.OWNER);

    Map<String, Object> data = AgreementDocumentMapper.toTemplateData(ownerOnly);

    assertThat(data).containsEntry("ownerName", "Asha Owner");
    assertThat(data.get("tenantName")).isNull();
    assertThat(data.get("tenantFatherName")).isNull();
    assertThat(data.get("tenantAddress")).isNull();
  }
}

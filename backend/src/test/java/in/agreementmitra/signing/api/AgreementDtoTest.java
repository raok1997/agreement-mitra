package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.Agreement;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure unit tests for request bean-validation and entity → response mapping. No Spring, no I/O. */
class AgreementDtoTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private static CreateAgreementRequest valid() {
    return new CreateAgreementRequest(
        "Asha Landlord",
        "Ravi Tenant",
        "12 MG Road, Bengaluru 560001",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        11);
  }

  @Test
  void validRequestHasNoViolations() {
    assertThat(validator.validate(valid())).isEmpty();
  }

  @Test
  void blankRequiredTextIsRejected() {
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "  ", "Ravi", "Addr", new BigDecimal("1"), new BigDecimal("1"), 11)))
        .isNotEmpty();
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "Asha", "", "Addr", new BigDecimal("1"), new BigDecimal("1"), 11)))
        .isNotEmpty();
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "Asha", "Ravi", "  ", new BigDecimal("1"), new BigDecimal("1"), 11)))
        .isNotEmpty();
  }

  @Test
  void negativeMoneyIsRejected() {
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "Asha", "Ravi", "Addr", new BigDecimal("-1"), new BigDecimal("1"), 11)))
        .isNotEmpty();
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "Asha", "Ravi", "Addr", new BigDecimal("1"), new BigDecimal("-1"), 11)))
        .isNotEmpty();
  }

  @Test
  void nonPositiveTermIsRejected() {
    assertThat(
            validator.validate(
                new CreateAgreementRequest(
                    "Asha", "Ravi", "Addr", new BigDecimal("1"), new BigDecimal("1"), 0)))
        .isNotEmpty();
  }

  @Test
  void responseMapsEveryFieldFromTheEntity() {
    Agreement entity =
        Agreement.create(
            "Asha", "Ravi", "Addr", new BigDecimal("25000.00"), new BigDecimal("50000.00"), 11);

    AgreementResponse response = AgreementResponse.from(entity);

    assertThat(response.id()).isEqualTo(entity.getId());
    assertThat(response.landlordName()).isEqualTo("Asha");
    assertThat(response.tenantName()).isEqualTo("Ravi");
    assertThat(response.propertyAddress()).isEqualTo("Addr");
    assertThat(response.monthlyRent()).isEqualByComparingTo("25000.00");
    assertThat(response.securityDeposit()).isEqualByComparingTo("50000.00");
    assertThat(response.termMonths()).isEqualTo(11);
    assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
  }
}

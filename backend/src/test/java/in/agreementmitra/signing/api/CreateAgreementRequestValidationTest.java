package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for {@link CreateAgreementRequest} — a plain {@link Validator}, no
 * Spring context. Covers the per-field constraints and the {@link ValidSignerSet} cross-field
 * rules, including the validator's null/empty safety.
 */
class CreateAgreementRequestValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static SignerRequest owner() {
    return new SignerRequest("Asha Owner", "asha@example.com", Role.OWNER);
  }

  private static SignerRequest tenant() {
    return new SignerRequest("Tara Tenant", "tara@example.com", Role.TENANT);
  }

  private static CreateAgreementRequest withSigners(List<SignerRequest> signers) {
    return new CreateAgreementRequest(
        "12 MG Road, Bengaluru",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        11,
        signers);
  }

  @Test
  void validRequestPasses() {
    assertThat(validator.validate(withSigners(List.of(owner(), tenant())))).isEmpty();
  }

  @Test
  void missingTenantFails() {
    assertThat(validator.validate(withSigners(List.of(owner(), owner())))).isNotEmpty();
  }

  @Test
  void missingOwnerFails() {
    assertThat(validator.validate(withSigners(List.of(tenant(), tenant())))).isNotEmpty();
  }

  @Test
  void duplicateEmailFailsCaseInsensitively() {
    SignerRequest tenantDupOfOwner =
        new SignerRequest("Tara Tenant", "ASHA@example.com", Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), tenantDupOfOwner)))).isNotEmpty();
  }

  @Test
  void emptySignerListFailsWithoutNpe() {
    // The cross-field validator must not NPE on an empty list; @Size reports it.
    assertThat(validator.validate(withSigners(List.of()))).isNotEmpty();
  }

  @Test
  void tooManySignersFails() {
    List<SignerRequest> signers = new ArrayList<>();
    signers.add(tenant());
    IntStream.range(0, 20)
        .forEach(
            i ->
                signers.add(
                    new SignerRequest("Owner " + i, "owner" + i + "@example.com", Role.OWNER)));
    assertThat(signers).hasSize(21);
    assertThat(validator.validate(withSigners(signers))).isNotEmpty();
  }

  @Test
  void malformedEmailFails() {
    SignerRequest badEmail = new SignerRequest("Bad Email", "not-an-email", Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), badEmail)))).isNotEmpty();
  }

  @Test
  void negativeRentFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "12 MG Road, Bengaluru",
            new BigDecimal("-1.00"),
            new BigDecimal("50000.00"),
            11,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }

  @Test
  void zeroTermFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            0,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }

  @Test
  void tooManyMoneyFractionDigitsFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "12 MG Road, Bengaluru",
            new BigDecimal("1000.999"),
            new BigDecimal("50000.00"),
            11,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }

  @Test
  void blankPropertyAddressFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "  ",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            11,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }
}

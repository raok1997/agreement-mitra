package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for {@link CreateAgreementRequest} — a plain {@link Validator}, no
 * Spring context. Covers the per-field constraints (name parts, address, optional email/mobile,
 * dates) and the {@link ValidSignerSet} / {@link EndAfterStart} cross-field rules.
 */
class CreateAgreementRequestValidationTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final LocalDate END = LocalDate.of(2026, 12, 1);

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
    return new SignerRequest(
        "Asha", "Owner", "Ravi Owner", "1 A St", "asha@example.com", null, null, Role.OWNER);
  }

  private static SignerRequest tenant() {
    return new SignerRequest(
        "Tara", "Tenant", "Hari Tenant", "3 C St", "tara@example.com", null, null, Role.TENANT);
  }

  private static CreateAgreementRequest withSigners(List<SignerRequest> signers) {
    return new CreateAgreementRequest(
        "12 MG Road, Bengaluru",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        START,
        END,
        signers);
  }

  @Test
  void validRequestPasses() {
    assertThat(validator.validate(withSigners(List.of(owner(), tenant())))).isEmpty();
  }

  @Test
  void contactlessPartiesStillPass() {
    // Contact is optional at draft (enforced only before signing).
    SignerRequest contactlessOwner =
        new SignerRequest("Asha", "Owner", "Ravi Owner", "1 A St", null, null, null, Role.OWNER);
    SignerRequest contactlessTenant =
        new SignerRequest("Tara", "Tenant", "Hari Tenant", "3 C St", null, null, null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(contactlessOwner, contactlessTenant))))
        .isEmpty();
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
        new SignerRequest(
            "Tara", "Tenant", "Hari Tenant", "3 C St", "ASHA@example.com", null, null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), tenantDupOfOwner)))).isNotEmpty();
  }

  @Test
  void emptySignerListFailsWithoutNpe() {
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
                    new SignerRequest(
                        "Owner",
                        "Num" + i,
                        "Father " + i,
                        "Addr " + i,
                        "owner" + i + "@example.com",
                        null,
                        null,
                        Role.OWNER)));
    assertThat(signers).hasSize(21);
    assertThat(validator.validate(withSigners(signers))).isNotEmpty();
  }

  @Test
  void malformedEmailFails() {
    SignerRequest badEmail =
        new SignerRequest(
            "Bad", "Email", "Father E", "Addr E", "not-an-email", null, null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), badEmail)))).isNotEmpty();
  }

  @Test
  void invalidMobileFails() {
    SignerRequest badMobile =
        new SignerRequest(
            "Bad", "Mobile", "Father M", "Addr M", null, "not-a-number", null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), badMobile)))).isNotEmpty();
  }

  @Test
  void blankFirstNameFails() {
    SignerRequest blank =
        new SignerRequest("  ", "Tenant", "Hari Tenant", "3 C St", null, null, null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), blank)))).isNotEmpty();
  }

  @Test
  void blankFatherNameOrAddressFails() {
    SignerRequest blankFather =
        new SignerRequest("Tara", "Tenant", "  ", "3 C St", null, null, null, Role.TENANT);
    SignerRequest blankAddress =
        new SignerRequest("Tara", "Tenant", "Hari Tenant", "  ", null, null, null, Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), blankFather)))).isNotEmpty();
    assertThat(validator.validate(withSigners(List.of(owner(), blankAddress)))).isNotEmpty();
  }

  @Test
  void blankNameOverrideFails() {
    SignerRequest blankOverride =
        new SignerRequest("Tara", "Tenant", "Hari Tenant", "3 C St", null, null, "  ", Role.TENANT);
    assertThat(validator.validate(withSigners(List.of(owner(), blankOverride)))).isNotEmpty();
  }

  @Test
  void negativeRentFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "12 MG Road, Bengaluru",
            new BigDecimal("-1.00"),
            new BigDecimal("50000.00"),
            START,
            END,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }

  @Test
  void endNotAfterStartFails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            END,
            START,
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
            START,
            END,
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
            START,
            END,
            List.of(owner(), tenant()));
    assertThat(validator.validate(request)).isNotEmpty();
  }
}

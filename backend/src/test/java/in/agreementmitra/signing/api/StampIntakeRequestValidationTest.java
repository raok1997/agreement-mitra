package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for {@link StampIntakeRequest} - a plain {@link Validator}, no Spring
 * context.
 *
 * <p>The load-bearing case is the certificate number's CHARACTER SET. It is deliberately permissive
 * about the SHCIL format (which varies by state) but strict about which characters may appear, and
 * the staff console mirrors the same shape at the field so an operator learns about a bad character
 * there rather than from a generic 400 that reads like a rejected scan. These tests pin the
 * server-side contract that mirror is copied from.
 */
class StampIntakeRequestValidationTest {

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

  private static StampIntakeRequest withCertificateNumber(String certificateNumber) {
    return new StampIntakeRequest(
        "AM7K3QPW9Z4",
        certificateNumber,
        LocalDate.of(2026, 1, 15),
        new BigDecimal("100"),
        "KA",
        null,
        null,
        false);
  }

  private static Set<String> violatedFields(StampIntakeRequest request) {
    return validator.validate(request).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  @Test
  void acceptsTheShapesShcilActuallyIssues() {
    assertThat(violatedFields(withCertificateNumber("IN-KA1234567890123"))).isEmpty();
    assertThat(violatedFields(withCertificateNumber("IN/TG/2026/000123"))).isEmpty();
    // Surrounding whitespace is tolerated - a staff-typed value routinely carries it, and it is
    // stripped by normalisation downstream.
    assertThat(violatedFields(withCertificateNumber("  IN-KA1234567890123  "))).isEmpty();
  }

  @Test
  void rejectsAnUnderscoreInTheCertificateNumber() {
    // The console pre-fills this field from the tracking reference; appending a suffix with an
    // underscore is the slip this refuses. Underscore is outside the accepted set on purpose.
    assertThat(violatedFields(withCertificateNumber("AM7K3QPW9Z4_STAMP")))
        .containsExactly("certificateNumber");
  }

  @Test
  void rejectsAnEmptyOrTooShortCertificateNumber() {
    assertThat(violatedFields(withCertificateNumber(""))).contains("certificateNumber");
    assertThat(violatedFields(withCertificateNumber("AB12"))).contains("certificateNumber");
  }

  @Test
  void requiresTheMandatoryCertificateFields() {
    StampIntakeRequest empty =
        new StampIntakeRequest(null, null, null, null, null, null, null, false);
    assertThat(violatedFields(empty))
        .contains(
            "agreementReference", "certificateNumber", "issueDate", "dutyAmount", "jurisdiction");
  }

  @Test
  void toStringCarriesNothing() {
    // PII contract: the certificate number evidences duty payment and the optional fields name a
    // party and describe the property. None of them may reach a log through a record's rendering.
    assertThat(withCertificateNumber("IN-KA1234567890123").toString())
        .isEqualTo("StampIntakeRequest{}");
  }
}

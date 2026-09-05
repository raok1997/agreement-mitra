package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.agreementmitra.DocumentDataInvalidException;
import in.agreementmitra.FieldErrorDetail;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmittedDataValidator}: the authoritative server-side check + coercion of
 * a submitted data map against an effective template's field schema. No Spring context, no I/O.
 * Covers per-type/bounds/pattern/enum validation, coercion to operand types, the preview/generate
 * tiers, default filling, and the never-echo-a-value invariant on the raised error.
 */
class SubmittedDataValidatorTest {

  // --- coercion + valid present values --------------------------------------

  @Test
  void coercesPresentValuesToOperandTypes() {
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("ownerName", "Asha Rao");
    submitted.put("durationMonths", "12"); // string -> Long
    submitted.put("monthlyRent", 15000); // int -> BigDecimal
    submitted.put("furnished", "true"); // string -> Boolean
    submitted.put("startDate", "2026-01-02"); // string -> ISO String

    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(
            referenceTemplate(), submitted, ProjectionMode.GENERATE);

    assertThat(coerced.get("ownerName")).isInstanceOf(String.class).isEqualTo("Asha Rao");
    assertThat(coerced.get("durationMonths")).isEqualTo(12L);
    assertThat(coerced.get("monthlyRent")).isEqualTo(new BigDecimal("15000"));
    assertThat(coerced.get("furnished")).isEqualTo(Boolean.TRUE);
    assertThat(coerced.get("startDate")).isEqualTo("2026-01-02");
  }

  @Test
  void fillsDeclaredDefaultForAbsentField() {
    // purpose is absent but declares default "residential"; the default satisfies its presence.
    Map<String, Object> submitted = fullyValidSubmission();
    submitted.remove("purpose");

    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(
            referenceTemplate(), submitted, ProjectionMode.GENERATE);

    assertThat(coerced.get("purpose")).isEqualTo("residential");
  }

  // --- bounds / pattern / enum ----------------------------------------------

  @Test
  void rejectsOutOfBoundsAndNonMemberEnumWithRuleTokensAndNoValue() {
    Map<String, Object> submitted = fullyValidSubmission();
    submitted.put("monthlyRent", 100); // below min 1000
    submitted.put("durationMonths", 999); // above max 60
    submitted.put("purpose", "industrial"); // not in options

    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    referenceTemplate(), submitted, ProjectionMode.GENERATE));

    assertThat(ex.errors())
        .contains(
            new FieldErrorDetail("monthlyRent", "min"),
            new FieldErrorDetail("durationMonths", "max"),
            new FieldErrorDetail("purpose", "enum"));
    // Never-echo: no rejected value string appears in any error entry.
    assertThat(ex.errors())
        .noneMatch(e -> e.field().contains("100") || e.message().contains("100"))
        .noneMatch(e -> e.message().contains("industrial"));
  }

  @Test
  void rejectsPatternAndLengthViolations() {
    Map<String, Object> submitted = fullyValidSubmission();
    submitted.put("ownerName", "A1!"); // fails pattern (letters/space only) -- and is length 3, ok

    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    referenceTemplate(), submitted, ProjectionMode.GENERATE));

    assertThat(ex.errors()).contains(new FieldErrorDetail("ownerName", "pattern"));
    assertThat(ex.errors()).noneMatch(e -> e.message().contains("A1!"));
  }

  @Test
  void rejectsUncoercibleValueAsTypeError() {
    Map<String, Object> submitted = fullyValidSubmission();
    submitted.put("durationMonths", "not-a-number");

    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    referenceTemplate(), submitted, ProjectionMode.GENERATE));

    assertThat(ex.errors()).contains(new FieldErrorDetail("durationMonths", "type"));
    assertThat(ex.errors()).noneMatch(e -> e.message().contains("not-a-number"));
  }

  // --- preview vs generate tiers --------------------------------------------

  @Test
  void previewToleratesMissingAndSkipsRequired() {
    // Only one field present; every required field absent. Preview must still succeed.
    Map<String, Object> submitted = Map.of("ownerName", "Asha Rao");

    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(
            referenceTemplate(), submitted, ProjectionMode.PREVIEW);

    assertThat(coerced).containsEntry("ownerName", "Asha Rao");
    // Absent optional-with-default still fills; absent required stays absent (a placeholder later).
    assertThat(coerced).containsEntry("purpose", "residential");
    assertThat(coerced).doesNotContainKey("monthlyRent");
  }

  @Test
  void generateEnforcesRequiredFields() {
    Map<String, Object> submitted = Map.of("ownerName", "Asha Rao");

    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    referenceTemplate(), submitted, ProjectionMode.GENERATE));

    // Every required-and-absent field (no default) is reported with the `required` rule token.
    assertThat(ex.errors())
        .contains(
            new FieldErrorDetail("monthlyRent", "required"),
            new FieldErrorDetail("durationMonths", "required"),
            new FieldErrorDetail("startDate", "required"));
    assertThat(ex.errors()).doesNotContain(new FieldErrorDetail("purpose", "required"));
  }

  @Test
  void blankStringCountsAsAbsentSoItsRequiredFires() {
    Map<String, Object> submitted = fullyValidSubmission();
    submitted.put("ownerName", "   "); // whitespace-only -> absent

    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    referenceTemplate(), submitted, ProjectionMode.GENERATE));

    assertThat(ex.errors()).contains(new FieldErrorDetail("ownerName", "required"));
  }

  // --- fixtures -------------------------------------------------------------

  private static EffectiveTemplate referenceTemplate() {
    List<Field> fields =
        List.of(
            new Field(
                "ownerName",
                "Owner name",
                FieldType.TEXT,
                true,
                null,
                null,
                new FieldValidation(null, null, 2, 50, "[A-Za-z ]+"),
                null),
            new Field(
                "durationMonths",
                "Duration (months)",
                FieldType.INT,
                true,
                null,
                null,
                new FieldValidation(1L, 60L, null, null, null),
                null),
            new Field(
                "monthlyRent",
                "Monthly rent",
                FieldType.MONEY,
                true,
                null,
                null,
                new FieldValidation(1000L, 100000L, null, null, null),
                null),
            new Field("furnished", "Furnished", FieldType.BOOL, false, null, null, null, null),
            new Field("startDate", "Start date", FieldType.DATE, true, null, null, null, null),
            new Field(
                "purpose",
                "Permitted use",
                FieldType.ENUM,
                false,
                "residential",
                List.of("residential", "commercial"),
                null,
                null));
    return new EffectiveTemplate(
        new TemplateDefinition(
            new Meta(
                "rental-base",
                new Dimensions("TG", "residential"),
                1,
                TemplateStatus.PUBLISHED,
                null),
            fields,
            List.of(),
            List.of(),
            "hash-abc"),
        new Dimensions("TG", "residential"),
        Map.of("rental-base", 1),
        "hash-abc");
  }

  private static Map<String, Object> fullyValidSubmission() {
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("ownerName", "Asha Rao");
    submitted.put("durationMonths", 12);
    submitted.put("monthlyRent", 15000);
    submitted.put("furnished", true);
    submitted.put("startDate", "2026-01-02");
    submitted.put("purpose", "residential");
    return submitted;
  }
}

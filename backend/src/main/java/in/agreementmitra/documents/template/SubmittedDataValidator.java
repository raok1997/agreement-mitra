package in.agreementmitra.documents.template;

import in.agreementmitra.DocumentDataInvalidException;
import in.agreementmitra.FieldErrorDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a submitted data map against an {@link EffectiveTemplate}'s field schema and returns a
 * <b>coerced</b> value map ready for the compiler (design D5). The authoritative server-side
 * counterpart to form projection's client-side validation metadata: a value that reaches here is
 * checked against its declared {@link FieldType} and its {@link FieldValidation}, and normalized to
 * the operand type the resolver validated ({@code INT -> Long}, {@code MONEY -> BigDecimal}, {@code
 * BOOL -> Boolean}, {@code DATE -> ISO String}, {@code TEXT}/{@code LONGTEXT}/{@code ENUM ->
 * String}) so {@code showWhen} evaluation and slot rendering see typed data, never raw JSON.
 *
 * <p>Two tiers ({@link ProjectionMode}): {@link ProjectionMode#PREVIEW} validates present values
 * and tolerates missing ones (placeholders, no {@code required} enforcement); {@link
 * ProjectionMode#GENERATE} enforces every {@code required} field is present-or-defaulted and valid.
 * A field with a declared default that is absent from the submission is filled from the default
 * (system-authored, already type-consistent), so the default satisfies {@code required}.
 *
 * <p>Every failure cites a field {@code key} + a rule token ({@code required} / {@code type} /
 * {@code min} / {@code max} / {@code minLength} / {@code maxLength} / {@code pattern} / {@code
 * enum}); it <b>never</b> carries a rejected value -- PII/never-echo. All violations are collected
 * and thrown once as a {@link DocumentDataInvalidException}.
 */
final class SubmittedDataValidator {

  private SubmittedDataValidator() {}

  /**
   * Validate {@code submitted} against {@code effective}'s field schema in {@code mode} and return
   * a coerced, default-filled value map (key -> typed value) for every field that has a value.
   *
   * @throws DocumentDataInvalidException if any field is invalid or a required field is missing
   *     (generate mode)
   */
  static Map<String, Object> validateAndCoerce(
      EffectiveTemplate effective, Map<String, Object> submitted, ProjectionMode mode) {
    Map<String, Object> data = submitted == null ? Map.of() : submitted;
    Map<String, Object> coerced = new LinkedHashMap<>();
    List<FieldErrorDetail> errors = new ArrayList<>();

    for (Field field : effective.template().fields()) {
      Object raw = present(data.get(field.key()));
      if (raw == null) {
        if (field.defaultValue() != null) {
          coerced.put(field.key(), field.defaultValue());
        } else if (mode == ProjectionMode.GENERATE && field.required()) {
          errors.add(new FieldErrorDetail(field.key(), "required"));
        }
        continue; // absent + optional (or absent required in preview): a placeholder, no check
      }
      Object value = coerce(field, raw, errors);
      if (value != null) {
        validateConstraints(field, value, errors);
        coerced.put(field.key(), value);
      }
    }

    if (!errors.isEmpty()) {
      throw new DocumentDataInvalidException(errors);
    }
    return coerced;
  }

  /** A blank/whitespace-only string counts as absent so its placeholder fires. */
  private static Object present(Object raw) {
    if (raw instanceof String s && s.isBlank()) {
      return null;
    }
    return raw;
  }

  /** Coerce a raw JSON value to the field's operand type, or record a {@code type} error. */
  private static Object coerce(Field field, Object raw, List<FieldErrorDetail> errors) {
    return switch (field.type()) {
      case TEXT, LONGTEXT -> raw instanceof String s ? s : typeError(field, errors);
      case ENUM -> raw instanceof String s ? s : typeError(field, errors);
      case BOOL -> coerceBool(field, raw, errors);
      case INT -> coerceInt(field, raw, errors);
      case MONEY -> coerceMoney(field, raw, errors);
      case DATE -> coerceDate(field, raw, errors);
    };
  }

  private static Object coerceBool(Field field, Object raw, List<FieldErrorDetail> errors) {
    if (raw instanceof Boolean b) {
      return b;
    }
    if (raw instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
      return Boolean.valueOf(s.equalsIgnoreCase("true"));
    }
    return typeError(field, errors);
  }

  private static Object coerceInt(Field field, Object raw, List<FieldErrorDetail> errors) {
    if (raw instanceof Integer i) {
      return i.longValue();
    }
    if (raw instanceof Long l) {
      return l;
    }
    if (raw instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) {
        return (long) d;
      }
      return typeError(field, errors);
    }
    if (raw instanceof String s) {
      try {
        return Long.valueOf(s.trim());
      } catch (NumberFormatException e) {
        return typeError(field, errors);
      }
    }
    return typeError(field, errors);
  }

  private static Object coerceMoney(Field field, Object raw, List<FieldErrorDetail> errors) {
    if (raw instanceof BigDecimal d) {
      return d;
    }
    if (raw instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    if (raw instanceof String s) {
      try {
        return new BigDecimal(s.trim());
      } catch (NumberFormatException e) {
        return typeError(field, errors);
      }
    }
    return typeError(field, errors);
  }

  private static Object coerceDate(Field field, Object raw, List<FieldErrorDetail> errors) {
    if (raw instanceof String s) {
      try {
        return LocalDate.parse(s.trim()).toString(); // normalize to ISO-8601, keep as String
      } catch (DateTimeParseException e) {
        return typeError(field, errors);
      }
    }
    return typeError(field, errors);
  }

  private static Object typeError(Field field, List<FieldErrorDetail> errors) {
    errors.add(new FieldErrorDetail(field.key(), "type"));
    return null;
  }

  /** Apply the field's {@link FieldValidation} and enum membership to the coerced value. */
  private static void validateConstraints(
      Field field, Object value, List<FieldErrorDetail> errors) {
    if (field.type() == FieldType.ENUM
        && field.options() != null
        && !field.options().contains(value)) {
      errors.add(new FieldErrorDetail(field.key(), "enum"));
    }
    FieldValidation v = field.validation();
    if (v == null) {
      return;
    }
    if (value instanceof Long || value instanceof BigDecimal) {
      BigDecimal number = value instanceof BigDecimal bd ? bd : BigDecimal.valueOf((Long) value);
      if (v.min() != null && number.compareTo(BigDecimal.valueOf(v.min())) < 0) {
        errors.add(new FieldErrorDetail(field.key(), "min"));
      }
      if (v.max() != null && number.compareTo(BigDecimal.valueOf(v.max())) > 0) {
        errors.add(new FieldErrorDetail(field.key(), "max"));
      }
    }
    if (value instanceof String s
        && (field.type() == FieldType.TEXT || field.type() == FieldType.LONGTEXT)) {
      if (v.minLength() != null && s.length() < v.minLength()) {
        errors.add(new FieldErrorDetail(field.key(), "minLength"));
      }
      if (v.maxLength() != null && s.length() > v.maxLength()) {
        errors.add(new FieldErrorDetail(field.key(), "maxLength"));
      }
      if (v.pattern() != null && !matches(v.pattern(), s)) {
        errors.add(new FieldErrorDetail(field.key(), "pattern"));
      }
    }
  }

  private static boolean matches(String pattern, String value) {
    try {
      return Pattern.compile(pattern).matcher(value).matches();
    } catch (PatternSyntaxException e) {
      // A malformed pattern is an authoring fault, not a user one; fail the value closed rather
      // than throwing (never leaks the value).
      return false;
    }
  }
}

package in.agreementmitra.signing.api;

import in.agreementmitra.PlausibleDates;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

/**
 * Validates {@link PlausibleDate} against the single shared range in {@link PlausibleDates}.
 * Null-safe: a null date is {@code @NotNull}'s to report.
 *
 * <p>{@code public} with a public no-arg constructor so a standalone {@code
 * jakarta.validation.Validator} (the no-Spring unit-test path) can instantiate it.
 */
public class PlausibleDateValidator implements ConstraintValidator<PlausibleDate, LocalDate> {

  @Override
  public boolean isValid(LocalDate date, ConstraintValidatorContext context) {
    return date == null || PlausibleDates.isPlausible(date);
  }
}

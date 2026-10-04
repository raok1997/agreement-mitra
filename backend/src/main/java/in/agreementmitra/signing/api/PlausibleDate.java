package in.agreementmitra.signing.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level constraint: a tenancy date SHALL fall within {@link
 * in.agreementmitra.PlausibleDates}' range. Null-safe -- a null date is owned by {@code @NotNull}.
 * See {@link PlausibleDateValidator}.
 */
@Documented
@Constraint(validatedBy = PlausibleDateValidator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface PlausibleDate {

  String message() default "Enter a date between 1900 and 2199.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}

package in.agreementmitra.signing.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link CreateAgreementRequest}: the tenancy {@code endDate} SHALL be
 * strictly after {@code startDate}. Null-safe — a null date is owned by {@code @NotNull}; this
 * constraint only judges ordering when both are present. See {@link EndAfterStartValidator}.
 */
@Documented
@Constraint(validatedBy = EndAfterStartValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface EndAfterStart {

  String message() default "The end date must be after the start date.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}

package in.agreementmitra.signing.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link CreateAgreementRequest}: the signer set SHALL contain at least
 * one {@code OWNER} and at least one {@code TENANT}, with no two signers sharing an email
 * (case-insensitive). Per-field rules ({@code @Size}, {@code @Email}, {@code @NotNull}) own
 * empty/null/malformed input; see {@link SignerSetValidator}.
 */
@Documented
@Constraint(validatedBy = SignerSetValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidSignerSet {

  String message() default
      "agreement must have at least one OWNER and one TENANT, with no duplicate signer emails";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}

package in.agreementmitra.signing.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link EndAfterStart}. Null-safe: returns {@code true} when the request or either date
 * is null (those are owned by {@code @NotNull}), so it never NPEs and only enforces ordering when
 * both dates are present.
 *
 * <p>{@code public} with a public no-arg constructor so a standalone {@code
 * jakarta.validation.Validator} (the no-Spring unit-test path) can instantiate it.
 */
public class EndAfterStartValidator
    implements ConstraintValidator<EndAfterStart, CreateAgreementRequest> {

  @Override
  public boolean isValid(CreateAgreementRequest request, ConstraintValidatorContext context) {
    if (request == null || request.startDate() == null || request.endDate() == null) {
      return true;
    }
    return request.endDate().isAfter(request.startDate());
  }
}

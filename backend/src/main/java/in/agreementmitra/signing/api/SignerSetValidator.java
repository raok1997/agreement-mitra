package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the {@link ValidSignerSet} cross-field rules. Null-safe by design: a null request or a
 * null/empty signer list returns {@code true} so {@code @NotNull}/{@code @Size}/{@code @Email}
 * report those — this validator never NPEs on partial input and only judges the role mix and email
 * uniqueness.
 *
 * <p>{@code public} (not package-private) so a plain {@code jakarta.validation.Validator} — the
 * unit-test path, no Spring — can instantiate it: Bean Validation's default factory uses a public
 * no-arg constructor. (Spring's own factory tolerates package-private, but the standalone validator
 * does not.)
 */
public class SignerSetValidator
    implements ConstraintValidator<ValidSignerSet, CreateAgreementRequest> {

  @Override
  public boolean isValid(CreateAgreementRequest request, ConstraintValidatorContext context) {
    if (request == null) {
      return true;
    }
    List<SignerRequest> signers = request.signers();
    if (signers == null || signers.isEmpty()) {
      return true; // owned by @NotNull / @Size
    }

    boolean hasOwner = false;
    boolean hasTenant = false;
    boolean duplicateEmail = false;
    Set<String> seenEmails = new HashSet<>();

    for (SignerRequest signer : signers) {
      if (signer == null) {
        continue; // owned by @Valid element constraints
      }
      if (signer.role() == Role.OWNER) {
        hasOwner = true;
      } else if (signer.role() == Role.TENANT) {
        hasTenant = true;
      }
      String email = signer.email();
      if (email != null && !seenEmails.add(email.toLowerCase(Locale.ROOT))) {
        duplicateEmail = true;
      }
    }

    return hasOwner && hasTenant && !duplicateEmail;
  }
}

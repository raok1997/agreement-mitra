package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.SigningRequestQuery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts the package-private {@link SigningRequestRepository} to the module-root {@link
 * SigningRequestQuery} seam. Package-private bean implementing a public interface — Spring wires it
 * by type, so the agreement sub-package gets the seam without reaching into this package.
 */
@Component
class SigningRequestQueryAdapter implements SigningRequestQuery {

  private final SigningRequestRepository repository;

  SigningRequestQueryAdapter(SigningRequestRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean existsForAgreement(UUID agreementId) {
    return repository.existsByAgreementId(agreementId);
  }
}

package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.SigningCompletionView;
import in.agreementmitra.signing.SigningRequestQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapts the package-private {@link SigningRequestRepository} to the module-root {@link
 * SigningRequestQuery} seam. Package-private bean implementing a public interface — Spring wires it
 * by type, so the agreement sub-package gets the seam without reaching into this package.
 *
 * <p>The completion views are {@code @Transactional(readOnly = true)} and map to plain value
 * objects <em>inside</em> the transaction: {@code open-in-view: false}, so the lazy invitee
 * collection must be initialized before the boundary. No entity or lazy proxy crosses back out.
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

  @Override
  public Optional<SignatureStatus> currentStatusForAgreement(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .map(SigningRequest::status);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SigningCompletionView> completionViewFor(String providerDocumentId) {
    return repository.findByProviderDocumentId(providerDocumentId).map(this::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SigningCompletionView> completionViewForAgreement(UUID agreementId) {
    return repository.findFirstByAgreementIdOrderByCreatedAtDesc(agreementId).map(this::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> signedPdfKeyForAgreement(UUID agreementId) {
    return repository
        .findFirstByAgreementIdOrderByCreatedAtDesc(agreementId)
        .map(SigningRequest::signedPdfKey);
  }

  /**
   * Project the aggregate to the fulfilment view. Carries the <b>invited</b> address per party (not
   * the draft-time one) plus that party's own signing sub-state, which together are what makes an
   * address signing-verified.
   */
  private SigningCompletionView toView(SigningRequest request) {
    return new SigningCompletionView(
        request.getId(),
        request.agreementId(),
        request.status(),
        request.signedPdfKey(),
        request.invitees().stream()
            .map(i -> new SigningCompletionView.Party(i.signerId(), i.invitedEmail(), i.status()))
            .toList());
  }
}

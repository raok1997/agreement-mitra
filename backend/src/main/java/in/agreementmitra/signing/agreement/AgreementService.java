package in.agreementmitra.signing.agreement;

import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.api.CreateAgreementRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the agreement aggregate. Java-{@code public} so the {@code api}
 * controller (a different package in the same module) can inject it; still Modulith-internal (not
 * exported through a named interface).
 *
 * <p>Methods are {@code @Transactional} and map the entity to a response DTO <em>inside</em> the
 * transaction — {@code open-in-view: false}, so the lazy signer collection must be initialized
 * before the boundary or it would throw {@code LazyInitializationException}. No entity (or lazy
 * proxy) crosses back to the controller.
 */
@Service
public class AgreementService {

  private final AgreementRepository repository;

  AgreementService(AgreementRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public AgreementResponse create(CreateAgreementRequest request) {
    Agreement agreement =
        Agreement.create(
            request.propertyAddress(),
            request.monthlyRent(),
            request.securityDeposit(),
            request.termMonths());
    request.signers().forEach(s -> agreement.addSigner(s.name(), s.email(), s.role()));
    return toResponse(repository.save(agreement));
  }

  @Transactional(readOnly = true)
  public Optional<AgreementResponse> findById(UUID id) {
    return repository.findById(id).map(this::toResponse);
  }

  /**
   * The agreement's draft object-storage key, or empty if the agreement has no draft (or does not
   * exist). Internal accessor for the signing flow — the key is server-internal and is deliberately
   * NOT exposed on the public {@link AgreementResponse}.
   */
  @Transactional(readOnly = true)
  public Optional<String> draftPdfKey(UUID id) {
    return repository.findById(id).map(Agreement::draftPdfKey);
  }

  private AgreementResponse toResponse(Agreement agreement) {
    var signers =
        agreement.signers().stream()
            .map(s -> new SignerResponse(s.id(), s.name(), s.email(), s.role()))
            .toList();
    return new AgreementResponse(
        agreement.getId(),
        agreement.propertyAddress(),
        agreement.monthlyRent(),
        agreement.securityDeposit(),
        agreement.termMonths(),
        agreement.createdAt(),
        signers);
  }
}

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

  /**
   * The agreement's procured stamp data, or empty if none has been procured (or the agreement does
   * not exist). Internal accessor for the signing flow — deliberately NOT on the public {@link
   * AgreementResponse}, mirroring {@link #draftPdfKey}.
   */
  @Transactional(readOnly = true)
  public Optional<StampInfo> stampInfo(UUID id) {
    return repository.findById(id).map(Agreement::stampInfo);
  }

  /**
   * Attach server-procured stamp data to the agreement. Server-managed only (never
   * client-settable). Joins the caller's transaction (REQUIRED) so the stamp-attach and the
   * signing-request {@code STAMPED} transition commit together.
   */
  @Transactional
  public void attachStamp(UUID id, StampInfo stampInfo) {
    Agreement agreement =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("Agreement vanished: " + id));
    agreement.attachStamp(stampInfo);
    repository.save(agreement);
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

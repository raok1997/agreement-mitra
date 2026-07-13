package in.agreementmitra.signing.agreement;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
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
  private final TemplateCatalogApi templateCatalog;

  AgreementService(AgreementRepository repository, TemplateCatalogApi templateCatalog) {
    this.repository = repository;
    this.templateCatalog = templateCatalog;
  }

  @Transactional
  public AgreementResponse create(CreateAgreementRequest request) {
    // Resolve the catalog selection BEFORE any persistence so an unknown (state, type) rejects
    // cleanly with nothing stored. The server owns the template UUID end-to-end (never client-set).
    UUID selectedTemplateId = resolveSelectedTemplate(request.state(), request.type());
    Agreement agreement =
        Agreement.create(
            request.propertyAddress(),
            request.monthlyRent(),
            request.securityDeposit(),
            request.startDate(),
            request.endDate());
    request
        .signers()
        .forEach(
            s ->
                agreement.addSigner(
                    fullName(s),
                    s.firstName(),
                    s.lastName(),
                    s.fatherName(),
                    s.currentAddress(),
                    s.email(),
                    s.mobile(),
                    s.role()));
    if (selectedTemplateId != null) {
      agreement.selectTemplate(selectedTemplateId);
    }
    return toResponse(repository.save(agreement));
  }

  /**
   * Resolve the published catalog template for the client-picked {@code (state, type)} to its
   * server-owned UUID. Both dimensions must be present to select; when either is absent the
   * agreement keeps today's default behaviour (no selection, {@code null}). An {@code (state,
   * type)} that no published template covers is rejected via the app-wide no-oracle 404 contract --
   * the catalog is the dimension-validation authority, and the {@code GlobalExceptionHandler}
   * renders a fixed detail that never echoes the requested dimensions.
   */
  private UUID resolveSelectedTemplate(String state, String type) {
    if (isBlank(state) || isBlank(type)) {
      return null;
    }
    return templateCatalog
        .publishedTemplateIdFor(state, type)
        .map(UUID::fromString)
        .orElseThrow(
            () ->
                new ResourceNotFoundException("no published template for the selected dimensions"));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Full name as per Aadhaar: the override when supplied (non-blank), else first + last name. */
  private static String fullName(CreateAgreementRequest.SignerRequest s) {
    String override = s.name();
    if (override != null && !override.isBlank()) {
      return override.trim();
    }
    return (s.firstName().trim() + " " + s.lastName().trim()).trim();
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

  /**
   * Record the selected catalog template's id on the agreement. Server-managed only (never
   * client-settable): the id is sourced from the catalog selection at the {@code api} layer --
   * where it is validated as a published template (via {@code documents.api}'s {@code
   * TemplateCatalogApi}) -- and passed inward as a plain {@link UUID} value, so {@code signing}
   * holds no {@code documents.template} type (Modulith-clean). The effective-template hash +
   * layer-version pin stays at generate-as-draft (document projection) -- unchanged here.
   *
   * <p>INTEGRATION NOTE: the create/update request wiring that routes a client-chosen template
   * through catalog validation into this setter is deliberately NOT added in this CR (the
   * CreateAgreementRequest / controller surface is being edited concurrently). This setter is the
   * server-managed call site; wiring it into the create flow is a flagged follow-up.
   */
  @Transactional
  public void recordSelectedTemplate(UUID agreementId, UUID templateId) {
    Agreement agreement =
        repository
            .findById(agreementId)
            .orElseThrow(() -> new IllegalStateException("Agreement vanished: " + agreementId));
    agreement.selectTemplate(templateId);
    repository.save(agreement);
  }

  private AgreementResponse toResponse(Agreement agreement) {
    var signers =
        agreement.signers().stream()
            .map(
                s ->
                    new SignerResponse(
                        s.id(),
                        s.name(),
                        s.firstName(),
                        s.lastName(),
                        s.fatherName(),
                        s.currentAddress(),
                        s.email(),
                        s.mobile(),
                        s.role()))
            .toList();
    return new AgreementResponse(
        agreement.getId(),
        agreement.propertyAddress(),
        agreement.monthlyRent(),
        agreement.securityDeposit(),
        agreement.startDate(),
        agreement.endDate(),
        agreement.termMonths(),
        agreement.createdAt(),
        signers);
  }
}

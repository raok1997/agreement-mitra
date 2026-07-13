package in.agreementmitra.signing.agreement;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders an agreement into its rental-agreement document. Java-{@code public} so the {@code api}
 * controller (a sibling package) can inject it; still Modulith-internal to the signing module. It
 * depends only on the {@code documents} module's public {@link DocumentProjectionApi} interface --
 * no reach into that module's internals -- so {@code ModularityTests} stays green. The agreement is
 * mapped to the effective template's <b>declared field keys</b> ({@link AgreementDocumentMapper});
 * {@code documents} resolves + compiles the effective template from that data map.
 *
 * <p>Render is a read: it is {@code @Transactional(readOnly = true)} so the lazy signer collection
 * initializes inside the transaction (open-in-view is off), and it <b>stores nothing</b> -- the PDF
 * is produced on demand and returned. The bytes are never logged. At generate-as-draft the caller
 * renders via {@link #renderForDraft} (bytes + effective-template identity), stores the bytes as
 * the draft, then records the <b>reproducibility pin</b> via {@link #pinEffectiveTemplate} once
 * storage succeeds -- so a stored/signed draft can never be silently re-rendered against newer
 * layers.
 */
@Service
public class AgreementDocumentService {

  private final AgreementRepository repository;
  private final DocumentProjectionApi documentProjection;
  private final TemplateCatalogApi templateCatalog;

  AgreementDocumentService(
      AgreementRepository repository,
      DocumentProjectionApi documentProjection,
      TemplateCatalogApi templateCatalog) {
    this.repository = repository;
    this.documentProjection = documentProjection;
    this.templateCatalog = templateCatalog;
  }

  /**
   * Render the agreement's document and return the PDF bytes. Nothing is stored. Uses the generate
   * projection (full validation over the effective template's field schema); a persisted agreement
   * always carries its required fields.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional(readOnly = true)
  public byte[] renderPreview(UUID agreementId) {
    return render(agreementId).pdf();
  }

  /**
   * Render the agreement's document for generate-as-draft and return the PDF bytes <b>plus</b> the
   * {@link EffectiveTemplateIdentity} of the template rendered. Nothing is stored here; the caller
   * stores the bytes as the draft and then records the identity via {@link #pinEffectiveTemplate}
   * once storage succeeds. Uses the same full generate projection as {@link #renderPreview}.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional(readOnly = true)
  public DocumentProjectionResult renderForDraft(UUID agreementId) {
    return render(agreementId);
  }

  /**
   * Record the effective-template <b>reproducibility pin</b> ({@code contentHash} + layer versions)
   * on the agreement. Server-managed only -- called at generate-as-draft strictly <b>after</b> a
   * successful full render and draft storage, so a rejected/frozen generate pins nothing. Reuses
   * the existing generate-as-draft transition -- no new signing-status FSM state.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional
  public void pinEffectiveTemplate(UUID agreementId, EffectiveTemplateIdentity identity) {
    Agreement agreement =
        repository
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));
    // managed entity -- flushed on tx commit
    agreement.pinEffectiveTemplate(identity.contentHash(), identity.layerVersions());
  }

  private DocumentProjectionResult render(UUID agreementId) {
    Agreement agreement =
        repository
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));
    Map<String, Object> data = AgreementDocumentMapper.toTemplateData(agreement);
    // A unique, non-PII document reference (the agreement id, prefixed) ties the rendered artifact
    // to the eSign audit trail (design D4). Furniture only -- it does not affect the effective
    // template or its pin.
    String documentReference = "AM-" + agreementId;
    return documentProjection.generate(
        new DocumentProjectionRequest(dimensionsFor(agreement), data, null, documentReference));
  }

  /**
   * The {@code (state, type)} the agreement's selected template resolves to, or {@code null} when
   * no template was selected. When present, {@code documents} resolves the SELECTED effective
   * template (so a TG agreement renders the TG overlay, not the IN default) and the reproducibility
   * pin auto-corrects to that template's identity; when {@code null}, {@code documents} resolves
   * its own default -- preserving the pre-selection behaviour for agreements created without
   * dimensions. The chosen template's dimensions come from the public {@link
   * TemplateCatalogApi#detail} seam (no {@code documents.template} type crosses the module
   * boundary).
   */
  private DocumentDimensions dimensionsFor(Agreement agreement) {
    UUID templateId = agreement.templateId();
    if (templateId == null) {
      return null;
    }
    TemplateDetail.Dimensions dimensions =
        templateCatalog.detail(templateId.toString()).dimensions();
    return new DocumentDimensions(dimensions.state(), dimensions.type());
  }
}

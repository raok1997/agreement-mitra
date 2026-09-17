package in.agreementmitra.signing.agreement;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.StampRenderUnavailableException;
import in.agreementmitra.documents.DocumentRenderException;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateFormApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * layers. Stamp intake re-renders the same document with the certificate's duty amount through
 * {@link #renderForStamp}, which refuses to do so unless it can reproduce that pinned draft.
 */
@Service
public class AgreementDocumentService {

  private static final Logger log = LoggerFactory.getLogger(AgreementDocumentService.class);

  /** The system-sourced template field the attached certificate's duty amount fills. */
  static final String STAMP_DUTY_AMOUNT_KEY = "stampDutyAmount";

  /** The captured field whose value the header prints as the execution date. */
  private static final String AGREEMENT_DATE_KEY = "agreementDate";

  private final AgreementRepository repository;
  private final DocumentProjectionApi documentProjection;
  private final TemplateCatalogApi templateCatalog;
  private final TemplateFormApi templateForms;

  AgreementDocumentService(
      AgreementRepository repository,
      DocumentProjectionApi documentProjection,
      TemplateCatalogApi templateCatalog,
      TemplateFormApi templateForms) {
    this.repository = repository;
    this.documentProjection = documentProjection;
    this.templateCatalog = templateCatalog;
    this.templateForms = templateForms;
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
   * {@link EffectiveTemplateIdentity} of the template rendered and the execution date printed.
   * Nothing is stored here; the caller stores the bytes as the draft and then records the identity
   * and date via {@link #pinEffectiveTemplate(UUID, EffectiveTemplateIdentity, String)} once
   * storage succeeds. Uses the same full generate projection as {@link #renderPreview}.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional(readOnly = true)
  public DocumentProjectionResult renderForDraft(UUID agreementId) {
    return render(agreementId);
  }

  /**
   * Record the effective-template <b>reproducibility pin</b> ({@code contentHash} + layer versions)
   * on the agreement, recording no draft execution date. Prefer the three-argument form on the
   * generate path.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional
  public void pinEffectiveTemplate(UUID agreementId, EffectiveTemplateIdentity identity) {
    pinEffectiveTemplate(agreementId, identity, null);
  }

  /**
   * Record the effective-template <b>reproducibility pin</b> ({@code contentHash} + layer versions)
   * and the ISO execution date the rendered draft printed ({@link
   * DocumentProjectionResult#executionDate()}; {@code null} records none). Server-managed only --
   * called at generate-as-draft strictly <b>after</b> a successful full render and draft storage,
   * so a rejected/frozen generate pins nothing. Reuses the existing generate-as-draft transition --
   * no new signing-status FSM state.
   *
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional
  public void pinEffectiveTemplate(
      UUID agreementId, EffectiveTemplateIdentity identity, String executionDate) {
    Agreement agreement = find(agreementId);
    // managed entity -- flushed on tx commit
    agreement.pinEffectiveTemplate(
        identity.contentHash(),
        identity.layerVersions(),
        executionDate == null ? null : LocalDate.parse(executionDate));
  }

  /**
   * Re-render the agreement's instrument for stamping, with the attached certificate's {@code
   * dutyAmount} as the value of the system-sourced stamp duty field, so the executed deed states
   * the duty actually paid. Nothing is stored.
   *
   * <p>Returns <b>empty</b> -- and the caller composites onto the stored draft exactly as before --
   * when a re-render could not reproduce that draft: the stored draft is not a recorded render
   * (uploaded, or rendered before the execution date was recorded), no template was selected, or
   * the template resolved now is not the one pinned when the draft was rendered. Each fallback is
   * logged with the agreement id and a reason code only.
   *
   * <p>When the captured {@code agreementDate} is blank the draft printed the date it was rendered;
   * that recorded date is passed back so the re-render never prints the intake date instead.
   *
   * @throws StampRenderUnavailableException if the renderer is unavailable -- retryable, nothing
   *     has been written
   * @throws ResourceNotFoundException if the agreement does not exist (mapped to 404)
   */
  @Transactional(readOnly = true)
  public Optional<byte[]> renderForStamp(UUID agreementId, BigDecimal dutyAmount) {
    try {
      return reRenderStoredDraft(find(agreementId), dutyAmount);
    } catch (DocumentRenderException e) {
      throw new StampRenderUnavailableException("instrument re-render failed", e);
    }
  }

  /**
   * Re-render the stored draft with {@code stampDutyAmount} as the system-sourced stamp duty value,
   * reproducing everything else: same template (the pin must still resolve), same capture data and
   * sections, same reference, and the execution date the draft printed. Empty, with the reason
   * logged, when that cannot be guaranteed.
   *
   * @throws DocumentRenderException if the renderer is unavailable
   */
  private Optional<byte[]> reRenderStoredDraft(Agreement agreement, BigDecimal stampDutyAmount) {
    UUID agreementId = agreement.getId();
    LocalDate draftDate = agreement.draftExecutionDate();
    String pinnedHash = agreement.templateContentHash();
    if (draftDate == null || pinnedHash == null) {
      return fallBack(agreementId, "NOT_A_RECORDED_RENDER");
    }
    DocumentDimensions dimensions = dimensionsFor(agreement);
    if (dimensions == null) {
      return fallBack(agreementId, "NO_TEMPLATE");
    }
    if (!pinnedHash.equals(
        templateForms.formFor(dimensions.state(), dimensions.type()).contentHash())) {
      return fallBack(agreementId, "PIN_DRIFT");
    }

    Map<String, Object> data = dataFor(agreement);
    Object capturedDate = data.get(AGREEMENT_DATE_KEY);
    if (capturedDate == null || String.valueOf(capturedDate).isBlank()) {
      data.put(AGREEMENT_DATE_KEY, draftDate.toString());
    }
    DocumentProjectionResult result =
        documentProjection.generate(
            new DocumentProjectionRequest(
                dimensions, data, activeSectionsFor(agreement), agreement.trackingReference()),
            Map.of(STAMP_DUTY_AMOUNT_KEY, stampDutyAmount));
    if (!pinnedHash.equals(result.identity().contentHash())) {
      // The layers changed between the check above and the render: still drift.
      return fallBack(agreementId, "PIN_DRIFT");
    }
    return Optional.of(result.pdf());
  }

  private static Optional<byte[]> fallBack(UUID agreementId, String reason) {
    log.info(
        "Stamping agreement {} onto its stored draft without a re-render (reason {})",
        agreementId,
        reason);
    return Optional.empty();
  }

  private Agreement find(UUID agreementId) {
    return repository
        .findById(agreementId)
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + agreementId));
  }

  private DocumentProjectionResult render(UUID agreementId) {
    Agreement agreement = find(agreementId);
    // The agreement's ONE tracking reference is passed as the document reference; the projection
    // renders it (with the platform URL) as the body provenance line -- system-owned body content
    // that does not affect the effective template or its pin. It is the same value the customer was
    // given and the same value staff quote at stamp intake, so the paper and the people agree. The
    // full UUID stays the canonical audit tie.
    return documentProjection.generate(
        new DocumentProjectionRequest(
            dimensionsFor(agreement),
            dataFor(agreement),
            activeSectionsFor(agreement),
            agreement.trackingReference()));
  }

  /**
   * The render data map. Render from the STORED capture state when present so the stored/signed
   * draft matches the live preview (parity). The stored working-set map is the base; the
   * authoritative fixed columns are overlaid on top so a stale map entry can never override the
   * typed property/rent/deposit/dates/parties (D3 reconciliation). When no capture state exists
   * (legacy row / fixed-fields-only API client) fall back to the fixed-column mapping -- behaviour
   * unchanged for existing agreements. Returns a fresh, mutable map.
   */
  private static Map<String, Object> dataFor(Agreement agreement) {
    CaptureState capture = agreement.captureState();
    Map<String, Object> data =
        capture == null ? new LinkedHashMap<>() : new LinkedHashMap<>(capture.data());
    data.putAll(AgreementDocumentMapper.toTemplateData(agreement)); // fixed columns win
    return data;
  }

  /** The stored optional-section titles; {@code null} (none) when no capture state exists. */
  private static List<String> activeSectionsFor(Agreement agreement) {
    CaptureState capture = agreement.captureState();
    return capture == null ? null : capture.activeSections();
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

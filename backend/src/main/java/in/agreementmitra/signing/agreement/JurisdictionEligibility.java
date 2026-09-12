package in.agreementmitra.signing.agreement;

import in.agreementmitra.ConflictException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The <b>jurisdiction precondition</b> on paid fulfilment: may this agreement's duty jurisdiction
 * reach a step that commits us to something real?
 *
 * <p>Stamp duty is state law and there is no national rate, so an agreement without an eligible
 * duty jurisdiction has <b>no duty we can compute and no state in which staff could buy the
 * certificate</b>. The published terms guarantee no second bill after payment, so charging for one
 * would be an unbounded liability against a fulfilment path that does not exist.
 *
 * <p>It is evaluated at <b>four</b> steps - every point that commits us in a jurisdiction:
 *
 * <ol>
 *   <li><b>finalise</b> - places the order and puts the agreement in the staff stamp queue.
 *   <li><b>checkout</b> - where the customer's money moves.
 *   <li><b>e-stamp intake</b> - where a real SHCIL certificate is bought.
 *   <li><b>eSign initiation</b> - where a billable vendor transaction is incurred.
 * </ol>
 *
 * <p>The last two are not redundant with {@code PaymentGate}, which is their only other control: a
 * staff <b>waiver</b> satisfies that gate, so "paid" does not imply "fulfillable". The hazard here
 * is <b>undefined fulfilment</b>, not unpaid fulfilment, and a staff member acting deliberately is
 * a different threat model that warrants a different response rather than none.
 *
 * <p>The refusal is a <b>distinct</b> {@link ConflictException.Kind#JURISDICTION_UNSUPPORTED},
 * never folded into payment-required, for the reason {@code PaymentGate} gives about its own: an
 * operator staring at a 409 must be able to tell why the pipeline stopped, because a different
 * person fixes each one.
 *
 * <p><b>Temporary by design.</b> When the {@code state-stamp-duty-quoting} change lands, per-state
 * duty rules in the {@code rules} module become the source of eligibility and this allowlist is
 * superseded - not extended. That change also establishes a duty jurisdiction for an agreement
 * drafted from a <i>national</i> template, by having the customer choose the property's state; this
 * class is written against the <b>duty jurisdiction</b> rather than the template's state dimension
 * precisely so that remains possible without contradicting it.
 *
 * <p>Lives in {@code signing.agreement} so it can read the aggregate's package-private {@code
 * templateId()}. Java-{@code public} so the {@code payment}, {@code signingrequest} and stamp
 * packages can call it; still Modulith-internal.
 */
@Component
@EnableConfigurationProperties(JurisdictionProperties.class)
public class JurisdictionEligibility {

  private static final Logger log = LoggerFactory.getLogger(JurisdictionEligibility.class);

  private final JurisdictionProperties properties;
  private final AgreementRepository repository;
  private final TemplateCatalogApi templateCatalog;

  JurisdictionEligibility(
      JurisdictionProperties properties,
      AgreementRepository repository,
      TemplateCatalogApi templateCatalog) {
    this.properties = properties;
    this.repository = repository;
    this.templateCatalog = templateCatalog;
  }

  /**
   * Make the resolved allowlist observable without reading configuration files, the way {@code
   * PaymentGate.announceMode()} does for its mode. An operator can then see what the running
   * instance admits - including that an empty list refuses everything.
   */
  @PostConstruct
  void announceEligible() {
    log.info("Jurisdictions eligible for paid fulfilment: {}", eligible());
  }

  /** The jurisdictions admitted for paid fulfilment. Public by construction, never PII. */
  public Set<String> eligible() {
    return properties.eligible();
  }

  /**
   * Evaluate the precondition for one agreement. Callers MUST invoke this <b>before any side
   * effect</b> at the gated step - no order placed, no blob written, no provider called, no vendor
   * charge incurred.
   *
   * <p>An agreement that cannot be found is permitted through, exactly as {@code PaymentGate} does:
   * resolving it is the caller's job and its own 404 is the honest answer, so this never turns a
   * missing agreement into a jurisdiction problem.
   *
   * @throws ConflictException {@code JURISDICTION_UNSUPPORTED} when the agreement's duty
   *     jurisdiction is absent, unresolvable, or not on the allowlist
   */
  public void require(UUID agreementId) {
    Agreement agreement = repository.findById(agreementId).orElse(null);
    if (agreement == null) {
      return; // Not our 404 to raise.
    }
    String state = dutyJurisdictionOf(agreement);
    if (state == null || !properties.eligible().contains(state)) {
      // Fact only, no agreement id and no party data - the PaymentGate logging precedent. Makes a
      // configuration outage visible, and answers "which jurisdiction are customers asking for".
      log.info("Refused paid fulfilment: jurisdiction {} is not eligible", state);
      throw ConflictException.jurisdictionUnsupported(state, properties.eligible());
    }
  }

  /**
   * The agreement's duty jurisdiction, or {@code null} when none can be established.
   *
   * <p><b>Fails closed on null and on unresolvable.</b> A dimension-less agreement (a legacy row,
   * or a fixed-fields-only API client - {@code state}/{@code type} are documented optional on
   * create) has no pinned template and therefore no known state. {@code
   * AgreementDocumentService.dimensionsFor} treats that as "fall back to the {@code documents}
   * default", which is right for <i>rendering</i> and exactly wrong for <i>fulfilment</i>: an
   * agreement whose jurisdiction we cannot name is one whose duty we cannot compute, so "unknown"
   * is not "fine". That is a deliberate breaking change to a path that worked before.
   *
   * <p>Resolved through the non-throwing {@link TemplateCatalogApi#find} rather than {@code
   * detail}. {@code detail} throws for an unknown <b>or non-published</b> id, so an agreement
   * pinned to a since-unpublished template would refuse with a misleading <b>404</b> instead of the
   * jurisdiction 409 - even when its state was perfectly eligible.
   */
  private String dutyJurisdictionOf(Agreement agreement) {
    UUID templateId = agreement.templateId();
    if (templateId == null) {
      return null;
    }
    return templateCatalog
        .find(templateId.toString())
        .map(TemplateDetail::dimensions)
        .map(TemplateDetail.Dimensions::state)
        .filter(state -> !state.isBlank())
        .map(state -> state.trim().toUpperCase(java.util.Locale.ROOT))
        .orElse(null);
  }
}

package in.agreementmitra.signing.agreement;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateFormApi;
import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.StampDutyCalculator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Evaluates an agreement against the stamp duty rules (state-stamp-duty-quoting, design D4-D6): its
 * duty jurisdiction, the calculator's outcome, whether the rule may be charged, and the stamp
 * options. The one place eligibility, the quote endpoint and checkout all get their answer from, so
 * they cannot disagree.
 *
 * <p>Java-{@code public} so the payment and api packages can call it; still Modulith-internal.
 */
@Component
public class StampQuoting {

  /** Why an agreement is or is not payable. Only {@link #QUOTABLE} admits paid fulfilment. */
  public enum Status {
    /** No pinned template, an unresolvable one, or one whose dimensions cannot be described. */
    NO_JURISDICTION,
    /** The calculator returned Unsupported or NeedsAdjudication. */
    UNSUPPORTED,
    /** Quoted, but under a rule with no current counsel review and unreviewed rules not allowed. */
    NOT_CHARGEABLE,
    /** Quoted and chargeable, but no stamp medium can plan the legal duty. */
    UNPLANNABLE,
    QUOTABLE
  }

  /**
   * @param state the duty jurisdiction, or null when none can be established
   * @param quote present whenever the calculator quoted, even if not chargeable
   * @param options present whenever {@code quote} is
   * @param executionDate the date the quote was computed for; null when there is no quote
   */
  public record Evaluation(
      Status status,
      String state,
      Optional<DutyOutcome.Quoted> quote,
      Optional<StampOptions> options,
      LocalDate executionDate) {

    public boolean payable() {
      return status == Status.QUOTABLE;
    }
  }

  private final AgreementRepository agreements;
  private final TemplateCatalogApi templateCatalog;
  private final TemplateFormApi templateForms;
  private final StampDutyCalculator calculator;
  private final Clock clock;

  StampQuoting(
      AgreementRepository agreements,
      TemplateCatalogApi templateCatalog,
      TemplateFormApi templateForms,
      StampDutyCalculator calculator,
      ObjectProvider<Clock> clock) {
    this.agreements = agreements;
    this.templateCatalog = templateCatalog;
    this.templateForms = templateForms;
    this.calculator = calculator;
    this.clock = clock.getIfAvailable(() -> Clock.system(DutyBasisMapper.INDIA));
  }

  /** Empty when the agreement does not exist -- resolving that is the caller's job. */
  public Optional<Evaluation> evaluate(UUID agreementId) {
    return agreements.findById(agreementId).map(this::evaluate);
  }

  Evaluation evaluate(Agreement agreement) {
    Optional<TemplateDetail.Dimensions> dimensions = dimensionsOf(agreement);
    String state =
        dimensions
            .map(TemplateDetail.Dimensions::state)
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toUpperCase(Locale.ROOT))
            .orElse(null);
    if (state == null) {
      return new Evaluation(Status.NO_JURISDICTION, null, Optional.empty(), Optional.empty(), null);
    }
    TemplateDetail.Dimensions pinned = dimensions.get();
    Optional<DutyBasis> basis =
        DutyBasisMapper.from(agreement, pinned, () -> escalationDefault(pinned), clock);
    if (basis.isEmpty()) {
      return new Evaluation(
          Status.NO_JURISDICTION, state, Optional.empty(), Optional.empty(), null);
    }
    if (!(calculator.quote(basis.get()) instanceof DutyOutcome.Quoted quoted)) {
      return new Evaluation(Status.UNSUPPORTED, state, Optional.empty(), Optional.empty(), null);
    }
    StampOptions options = StampOptions.of(quoted);
    Status status =
        !calculator.isChargeable(quoted.rule())
            ? Status.NOT_CHARGEABLE
            : options.recommended().isEmpty() ? Status.UNPLANNABLE : Status.QUOTABLE;
    return new Evaluation(
        status, state, Optional.of(quoted), Optional.of(options), basis.get().executionDate());
  }

  /** Duty states a customer can currently be charged in; public by construction, never PII. */
  public Set<String> chargeableStates() {
    return calculator.chargeableStates();
  }

  /**
   * The deed's fallback for a blank escalation: the field's default in the template the pinned
   * dimensions resolve to, looked up with those dimensions exactly as carried (the catalog lookup
   * is case-sensitive).
   */
  private DutyBasisMapper.DefaultLookup escalationDefault(TemplateDetail.Dimensions pinned) {
    Optional<FormSchema> form = templateForms.findForm(pinned.state(), pinned.type());
    if (form.isEmpty()) {
      return DutyBasisMapper.DefaultLookup.NOT_FOUND;
    }
    return form.get().sections().stream()
        .flatMap(section -> section.fields().stream())
        .filter(field -> DutyBasisMapper.ESCALATION_PERCENT.equals(field.key()))
        .findFirst()
        .map(FormField::defaultValue)
        .filter(StampQuoting::isIntegral)
        .map(value -> DutyBasisMapper.DefaultLookup.of(((Number) value).longValue()))
        .orElse(DutyBasisMapper.DefaultLookup.NONE);
  }

  /** INT defaults arrive as {@code Long}; any other type is not a whole-number default. */
  private static boolean isIntegral(Object value) {
    return value instanceof Long || value instanceof Integer;
  }

  /**
   * Resolved through the non-throwing {@link TemplateCatalogApi#find}: an agreement pinned to a
   * since-unpublished template must refuse as a jurisdiction failure, not a misleading 404.
   */
  private Optional<TemplateDetail.Dimensions> dimensionsOf(Agreement agreement) {
    UUID templateId = agreement.templateId();
    if (templateId == null) {
      return Optional.empty();
    }
    return templateCatalog.find(templateId.toString()).map(TemplateDetail::dimensions);
  }
}

package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyLine;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.RuleRef;
import in.agreementmitra.rules.StampDutyCalculator;
import in.agreementmitra.rules.StampPlan;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base stamp duty calculator: one fixed pipeline for every state (design D4, D8).
 *
 * <p>Order: rule -> precheck -> quantities -> slab -> consideration -> rate or fixed -> the slab's
 * own min/max -> the rule's min/max -> extension adjust -> surcharges -> counterpart duty -> round
 * once -> catalog -> one stamp plan per medium. Exact decimals throughout; nothing is rounded to a
 * currency unit before the last duty step. Logs only the rule id and outcome type, never the facts.
 */
final class DutyEngine implements StampDutyCalculator {

  private static final Logger log = LoggerFactory.getLogger(DutyEngine.class);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final String NATIONAL = "IN";

  private final RuleSetRegistry rules;
  private final List<StampPaperCatalog> catalogs;
  private final Map<String, DutyExtension> extensions;
  private final StampPaperPlanner planner;
  private final boolean allowUnreviewed;

  DutyEngine(
      RuleSetRegistry rules,
      List<StampPaperCatalog> catalogs,
      List<DutyExtension> extensions,
      StampPaperPlanner planner,
      boolean allowUnreviewed) {
    this.allowUnreviewed = allowUnreviewed;
    this.rules = rules;
    this.catalogs = List.copyOf(catalogs);
    this.extensions =
        extensions.stream()
            .collect(Collectors.toUnmodifiableMap(DutyExtension::id, Function.identity()));
    this.planner = planner;
  }

  List<RuleSet> rules() {
    return rules.all();
  }

  @Override
  public boolean isChargeable(RuleRef rule) {
    return rule.reviewed() || allowUnreviewed;
  }

  @Override
  public Set<String> chargeableStates() {
    return rules.all().stream()
        .filter(r -> isChargeable(r.ref()))
        .map(RuleSet::state)
        .collect(Collectors.toCollection(java.util.TreeSet::new));
  }

  @Override
  public DutyOutcome quote(DutyBasis basis) {
    DutyOutcome outcome = compute(basis);
    log.debug(
        "stamp duty quote rule={} outcome={}",
        switch (outcome) {
          case DutyOutcome.Quoted q -> q.rule().id();
          case DutyOutcome.NeedsAdjudication n -> n.rule().id();
          case DutyOutcome.Unsupported u -> "-";
        },
        outcome.getClass().getSimpleName());
    return outcome;
  }

  private DutyOutcome compute(DutyBasis basis) {
    if (NATIONAL.equals(basis.dutyState())) {
      return new DutyOutcome.Unsupported(
          "IN is not a duty jurisdiction: no national stamp duty rate");
    }
    Optional<RuleSet> found =
        rules.find(basis.dutyState(), basis.instrumentKind(), basis.usage(), basis.executionDate());
    if (found.isEmpty()) {
      return new DutyOutcome.Unsupported(
          "no stamp duty rule for "
              + basis.dutyState()
              + " "
              + basis.instrumentKind()
              + " "
              + basis.usage()
              + " on "
              + basis.executionDate());
    }
    RuleSet rule = found.get();
    DutyExtension extension = rule.extension() == null ? null : extensions.get(rule.extension());
    if (rule.extension() != null && extension == null) {
      throw new IllegalStateException(
          "extension " + rule.extension() + " missing for " + rule.id());
    }

    if (extension != null) {
      Optional<DutyOutcome> refusal = extension.precheck(basis, rule);
      if (refusal.isPresent()) {
        if (refusal.get() instanceof DutyOutcome.Quoted) {
          throw new IllegalStateException("extension precheck may not quote: " + extension.id());
        }
        return refusal.get();
      }
    }

    Quantities quantities = Quantities.standard(basis, rule.params());
    if (extension != null) {
      quantities = extraQuantities(extension, quantities, basis, rule);
    }

    Optional<RuleSet.Slab> slabFound = rule.slabFor(basis.termMonths());
    if (slabFound.isEmpty()) {
      return new DutyOutcome.Unsupported(
          "rule " + rule.id() + " has no slab for a term of " + basis.termMonths() + " months");
    }
    RuleSet.Slab slab = slabFound.get();
    List<DutyLine> lines = new ArrayList<>();

    BigDecimal duty;
    if (slab.fixedAmount() != null) {
      duty = slab.fixedAmount();
      lines.add(new DutyLine(DutyLine.Kind.BASE, "fixed duty for " + slabLabel(slab), duty));
    } else {
      BigDecimal consideration = BigDecimal.ZERO;
      for (String name : slab.consideration()) {
        BigDecimal value = quantities.get(name);
        lines.add(new DutyLine(DutyLine.Kind.QUANTITY, name, value));
        consideration = consideration.add(value);
      }
      escalationLine(basis, rule, extension, slab, consideration).ifPresent(lines::add);
      duty = consideration.multiply(slab.ratePercent()).divide(HUNDRED, MathContext.DECIMAL128);
      lines.add(
          new DutyLine(
              DutyLine.Kind.BASE,
              plain(slab.ratePercent())
                  + "% of "
                  + plain(consideration)
                  + " ("
                  + slabLabel(slab)
                  + ")",
              duty));
    }

    // The selected slab's own bounds first, then the rule's. Order matters and is specified: the
    // rule bound is the OUTER bound on the quote, so a slab cap must never be able to defeat a rule
    // minimum. A slab bound also must not reach a term that falls in a different slab -- which is
    // the whole reason Karnataka's INR 500 residential cap cannot be written as a rule maximum.
    RuleSet.Bounds slabBounds = slab.bounds();
    if (slabBounds.minimum() != null && duty.compareTo(slabBounds.minimum()) < 0) {
      lines.add(
          delta(
              DutyLine.Kind.SLAB_MINIMUM,
              "minimum " + plain(slabBounds.minimum()) + " for " + slabLabel(slab),
              duty,
              slabBounds.minimum()));
      duty = slabBounds.minimum();
    }
    if (slabBounds.maximum() != null && duty.compareTo(slabBounds.maximum()) > 0) {
      lines.add(
          delta(
              DutyLine.Kind.SLAB_MAXIMUM,
              "maximum " + plain(slabBounds.maximum()) + " for " + slabLabel(slab),
              duty,
              slabBounds.maximum()));
      duty = slabBounds.maximum();
    }

    RuleSet.Bounds bounds = rule.bounds();
    if (bounds.minimum() != null && duty.compareTo(bounds.minimum()) < 0) {
      lines.add(
          delta(
              DutyLine.Kind.MINIMUM, "minimum " + plain(bounds.minimum()), duty, bounds.minimum()));
      duty = bounds.minimum();
    }
    if (bounds.maximum() != null && duty.compareTo(bounds.maximum()) > 0) {
      lines.add(
          delta(
              DutyLine.Kind.MAXIMUM, "maximum " + plain(bounds.maximum()), duty, bounds.maximum()));
      duty = bounds.maximum();
    }

    if (extension != null) {
      BigDecimal adjusted = extension.adjust(duty, basis, quantities, rule);
      if (adjusted.compareTo(duty) != 0) {
        lines.add(
            delta(DutyLine.Kind.EXTENSION, "adjustment by " + extension.id(), duty, adjusted));
        duty = adjusted;
      }
    }

    BigDecimal surchargeBase = duty;
    for (RuleSet.Surcharge surcharge : rule.surcharges()) {
      BigDecimal amount =
          surchargeBase.multiply(surcharge.percentOfDuty()).divide(HUNDRED, MathContext.DECIMAL128);
      lines.add(
          new DutyLine(
              DutyLine.Kind.SURCHARGE,
              surcharge.name() + " at " + plain(surcharge.percentOfDuty()) + "%",
              amount));
      duty = duty.add(amount);
    }

    if (basis.counterparts() > 1 && rule.counterpartDuty().signum() > 0) {
      BigDecimal amount =
          rule.counterpartDuty().multiply(BigDecimal.valueOf(basis.counterparts() - 1));
      lines.add(
          new DutyLine(
              DutyLine.Kind.COUNTERPART,
              (basis.counterparts() - 1) + " counterpart(s) at " + plain(rule.counterpartDuty()),
              amount));
      duty = duty.add(amount);
    }

    BigDecimal rounded = rule.rounding().apply(duty);
    lines.add(
        delta(
            DutyLine.Kind.ROUNDING,
            "rounded "
                + rule.rounding().mode()
                + " to "
                + rule.rounding().unitRupees()
                + " rupee(s)",
            duty,
            rounded));
    long amountPaise;
    try {
      amountPaise =
          rounded.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    } catch (ArithmeticException e) {
      return new DutyOutcome.Unsupported(
          "rule " + rule.id() + " yields a duty too large to represent in paise");
    }

    Optional<StampPaperCatalog> catalog = catalogFor(basis.dutyState(), basis.executionDate());
    if (catalog.isEmpty()) {
      return new DutyOutcome.Unsupported(
          "no stamp paper catalog for " + basis.dutyState() + " on " + basis.executionDate());
    }
    List<StampPlan> plans =
        catalog.get().media().stream().map(m -> planner.plan(amountPaise, m)).toList();

    return new DutyOutcome.Quoted(
        amountPaise,
        lines,
        rule.ref(),
        rule.registration().requiredFor(basis.termMonths()),
        plans,
        catalog.get().ref());
  }

  /**
   * The rupees escalation added to the slab's consideration, as an informational line, or empty
   * when it added nothing. Recomputed over the same quantity path without escalation; the precheck
   * is not re-run because the basis already passed it.
   */
  private static Optional<DutyLine> escalationLine(
      DutyBasis basis,
      RuleSet rule,
      DutyExtension extension,
      RuleSet.Slab slab,
      BigDecimal consideration) {
    if (basis.escalationPercent().signum() == 0 || basis.escalationEveryMonths() == 0) {
      return Optional.empty();
    }
    DutyBasis flat = basis.withoutEscalation();
    Quantities quantities = Quantities.standard(flat, rule.params());
    if (extension != null) {
      quantities = extraQuantities(extension, quantities, flat, rule);
    }
    BigDecimal withoutEscalation = BigDecimal.ZERO;
    for (String name : slab.consideration()) {
      withoutEscalation = withoutEscalation.add(quantities.get(name));
    }
    BigDecimal uplift = consideration.subtract(withoutEscalation).setScale(2, RoundingMode.HALF_UP);
    if (uplift.signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        new DutyLine(
            DutyLine.Kind.ESCALATION,
            plain(basis.escalationPercent())
                + "% rent escalation every "
                + basis.escalationEveryMonths()
                + " months",
            uplift));
  }

  private static Quantities extraQuantities(
      DutyExtension extension, Quantities before, DutyBasis basis, RuleSet rule) {
    Quantities after = extension.extraQuantities(before, basis, rule);
    Set<String> added = new HashSet<>(after.names());
    added.removeAll(before.names());
    if (!added.equals(extension.providedQuantities())) {
      throw new IllegalStateException(
          "extension "
              + extension.id()
              + " added "
              + added
              + " but declares "
              + extension.providedQuantities());
    }
    return after;
  }

  private Optional<StampPaperCatalog> catalogFor(String state, LocalDate date) {
    return catalogs.stream().filter(c -> c.state().equals(state) && c.inEffectOn(date)).findFirst();
  }

  private static DutyLine delta(DutyLine.Kind kind, String label, BigDecimal from, BigDecimal to) {
    return new DutyLine(kind, label, to.subtract(from));
  }

  private static String slabLabel(RuleSet.Slab slab) {
    return "term " + slab.minMonths() + "-" + slab.maxMonths() + " months";
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}

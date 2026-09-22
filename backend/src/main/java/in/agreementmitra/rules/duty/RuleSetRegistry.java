package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Selects the rule in effect for (state, instrument kind, usage) on an execution date. Loaded rules
 * are validated to have non-overlapping windows, so at most one matches. Never selects the national
 * dimension.
 */
final class RuleSetRegistry {

  private final List<RuleSet> rules;

  RuleSetRegistry(List<RuleSet> rules) {
    this.rules = List.copyOf(rules);
  }

  Optional<RuleSet> find(String state, InstrumentKind kind, Usage usage, LocalDate executionDate) {
    if ("IN".equals(state)) {
      return Optional.empty();
    }
    return rules.stream()
        .filter(r -> r.state().equals(state))
        .filter(r -> r.instrumentKind() == kind && r.usage() == usage)
        .filter(r -> r.inEffectOn(executionDate))
        .findFirst();
  }

  List<RuleSet> all() {
    return rules;
  }
}

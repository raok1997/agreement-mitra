package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyOutcome;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * Per-state hook for a rule that cannot be expressed as data (design D5). A Spring bean bound to a
 * rule through the rule's {@code extension:} id. It may refuse a fact pattern, contribute named
 * quantities, or adjust the bounded duty -- never reorder the pipeline.
 *
 * <p>{@link #providedQuantities()} lets the loader validate slab references at startup without
 * running the extension; the engine checks at runtime that {@link #extraQuantities} added exactly
 * those names.
 */
interface DutyExtension {

  String id();

  default Set<String> providedQuantities() {
    return Set.of();
  }

  /** A non-empty result ({@code NeedsAdjudication} or {@code Unsupported}) ends the quote. */
  default Optional<DutyOutcome> precheck(DutyBasis basis, RuleSet rule) {
    return Optional.empty();
  }

  default Quantities extraQuantities(Quantities quantities, DutyBasis basis, RuleSet rule) {
    return quantities;
  }

  /** Adjusts the duty after min/max; a change is recorded as an EXTENSION breakdown line. */
  default BigDecimal adjust(BigDecimal duty, DutyBasis basis, Quantities quantities, RuleSet rule) {
    return duty;
  }
}

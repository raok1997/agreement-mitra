package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import in.agreementmitra.rules.RuleRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One loaded, merged, validated stamp duty rule: a state, instrument kind and usage over an
 * effective window. Built only by {@link RuleSetLoader}.
 *
 * @param sourceName the file it came from, for error messages
 * @param extension id of the {@link DutyExtension} bound to this rule, or null
 * @param counselReview the review record as loaded (opaque here; excluded from the hash)
 */
record RuleSet(
    String id,
    String sourceName,
    String state,
    InstrumentKind instrumentKind,
    Usage usage,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String legalReference,
    String extension,
    Map<String, BigDecimal> params,
    List<Slab> slabs,
    Bounds bounds,
    List<Surcharge> surcharges,
    BigDecimal counterpartDuty,
    Rounding rounding,
    RegistrationRule registration,
    Map<String, Object> counselReview,
    List<RuleCase> cases,
    String contentHash) {

  RuleSet {
    params = Map.copyOf(params);
    slabs = List.copyOf(slabs);
    surcharges = List.copyOf(surcharges);
    cases = List.copyOf(cases);
  }

  RuleRef ref() {
    boolean reviewed =
        counselReview != null
            && contentHash != null
            && contentHash.equals(String.valueOf(counselReview.get("contentHash")));
    return new RuleRef(id, legalReference, contentHash, reviewed);
  }

  boolean inEffectOn(LocalDate date) {
    return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
  }

  Optional<Slab> slabFor(int termMonths) {
    return slabs.stream().filter(s -> s.contains(termMonths)).findFirst();
  }

  /**
   * A term band. Exactly one of {@code ratePercent} (applied to the summed consideration) or {@code
   * fixedAmount} (consideration empty) is set.
   */
  record Slab(
      int minMonths,
      int maxMonths,
      List<String> consideration,
      BigDecimal ratePercent,
      BigDecimal fixedAmount) {
    Slab {
      consideration = List.copyOf(consideration);
    }

    boolean contains(int termMonths) {
      return termMonths >= minMonths && termMonths <= maxMonths;
    }
  }

  /** Either bound may be null (absent). */
  record Bounds(BigDecimal minimum, BigDecimal maximum) {}

  record Surcharge(String name, BigDecimal percentOfDuty) {}

  /** Final rounding to a whole number of {@code unitRupees}. */
  record Rounding(Mode mode, int unitRupees) {

    enum Mode {
      UP(RoundingMode.UP),
      HALF_UP(RoundingMode.HALF_UP),
      DOWN(RoundingMode.DOWN);

      private final RoundingMode roundingMode;

      Mode(RoundingMode roundingMode) {
        this.roundingMode = roundingMode;
      }
    }

    BigDecimal apply(BigDecimal amount) {
      BigDecimal unit = BigDecimal.valueOf(unitRupees);
      return amount.divide(unit, 0, mode.roundingMode).multiply(unit);
    }
  }

  /** {@code requiredWhenTermMonthsOver} null means the rule never requires registration. */
  record RegistrationRule(Integer requiredWhenTermMonthsOver) {
    boolean requiredFor(int termMonths) {
      return requiredWhenTermMonthsOver != null && termMonths > requiredWhenTermMonthsOver;
    }
  }

  /** A worked example: facts and the outcome the rule must produce. */
  record RuleCase(String name, DutyBasis basis, Expectation expect) {}

  /**
   * @param amountPaise expected legal duty for {@link OutcomeType#QUOTED}, else null
   * @param planTotalsPaise medium id to expected plan total; a null value means unplannable. May be
   *     empty (plans not asserted).
   */
  record Expectation(OutcomeType outcome, Long amountPaise, Map<String, Long> planTotalsPaise) {
    Expectation {
      planTotalsPaise = Collections.unmodifiableMap(new LinkedHashMap<>(planTotalsPaise));
    }
  }

  enum OutcomeType {
    QUOTED,
    NEEDS_ADJUDICATION,
    UNSUPPORTED
  }
}

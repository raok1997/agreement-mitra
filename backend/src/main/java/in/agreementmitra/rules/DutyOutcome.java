package in.agreementmitra.rules;

import java.util.List;
import java.util.Objects;

/**
 * The result of a stamp duty quote: exactly one of three outcomes. Anything other than {@link
 * Quoted} means there is <b>no computable duty</b>; no outcome ever substitutes a zero amount.
 */
public sealed interface DutyOutcome
    permits DutyOutcome.Quoted, DutyOutcome.NeedsAdjudication, DutyOutcome.Unsupported {

  /**
   * A computed duty.
   *
   * @param amountPaise the <b>legal</b> duty; stamp planning never changes it
   * @param breakdown ordered steps that replay to {@code amountPaise} (see {@link DutyLine})
   * @param registrationRequired reported separately; never part of the duty amount
   * @param stampPlans one plan per stamp medium the duty state offers
   */
  record Quoted(
      long amountPaise,
      List<DutyLine> breakdown,
      RuleRef rule,
      boolean registrationRequired,
      List<StampPlan> stampPlans,
      CatalogRef catalog)
      implements DutyOutcome {
    public Quoted {
      breakdown = List.copyOf(breakdown);
      stampPlans = List.copyOf(stampPlans);
      Objects.requireNonNull(rule, "rule");
      Objects.requireNonNull(catalog, "catalog");
    }
  }

  /** A rule applies but declares this fact pattern non-computable (s.31-style assessment). */
  record NeedsAdjudication(String reason, RuleRef rule) implements DutyOutcome {}

  /** No reviewed rule or catalog can determine the amount. */
  record Unsupported(String reason) implements DutyOutcome {}
}

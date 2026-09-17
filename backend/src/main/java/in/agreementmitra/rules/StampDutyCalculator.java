package in.agreementmitra.rules;

import java.util.Set;

/**
 * Computes the stamp duty owed on a rental instrument, and the stamp paper that meets it, from
 * normalized facts and versioned per-state rule data. Pure: no I/O, no personal data, and the same
 * facts against the same rule data always produce the same outcome.
 */
public interface StampDutyCalculator {

  DutyOutcome quote(DutyBasis basis);

  /**
   * Whether a customer may be charged on a quote computed under this rule: the rule carries a
   * current counsel review, or unreviewed rules are explicitly allowed by configuration ({@code
   * rules.stamp-duty.allow-unreviewed}, default false).
   */
  boolean isChargeable(RuleRef rule);

  /**
   * Duty states with at least one loaded rule that is chargeable, for disclosure before drafting.
   */
  Set<String> chargeableStates();
}

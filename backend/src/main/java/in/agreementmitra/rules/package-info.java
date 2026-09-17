/**
 * Rules module: jurisdiction-specific legal logic. Today that is the <b>stamp duty calculator</b>:
 * {@link in.agreementmitra.rules.StampDutyCalculator} turns state-agnostic agreement facts ({@link
 * in.agreementmitra.rules.DutyBasis}) into a legal duty with an auditable breakdown, plus a stamp
 * paper plan per medium the state issues.
 *
 * <p><b>Engine choice:</b> a typed evaluator over declarative, content-hashed YAML rule data, not
 * Drools (change {@code stamp-duty-base-calculator}, design D1). Duty rules are closed-form
 * piecewise-linear formulas; a replayable breakdown and reviewable data matter more here than
 * inference.
 *
 * <p>Module API: the root package only. {@code rules.duty} (engine, loaders, planner, state
 * extensions) is internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Rules")
package in.agreementmitra.rules;

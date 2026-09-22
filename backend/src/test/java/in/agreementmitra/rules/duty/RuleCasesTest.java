package in.agreementmitra.rules.duty;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.rules.DutyLine;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.StampPlan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The build gate on worked cases: every {@code cases:} entry of every rule on the test classpath is
 * evaluated through the real engine, every slab boundary must have a case, and every Quoted
 * breakdown must replay to its amount. Adding a state rule automatically adds its checks here.
 */
class RuleCasesTest {

  private static final DutyEngine ENGINE =
      StampDutyConfiguration.build(
          TestRules.DEFAULT_RULE_LOCATIONS,
          TestRules.DEFAULT_CATALOG_LOCATIONS,
          List.of(new ZzTestExtension()),
          false);

  static Stream<RuleSet> rules() {
    return loadedRules().stream();
  }

  private static List<RuleSet> loadedRules() {
    return new RuleSetLoader(Map.of(ZzTestExtension.ID, new ZzTestExtension().providedQuantities()))
        .load(RuleSetLoader.resolve(TestRules.DEFAULT_RULE_LOCATIONS));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void everyDeclaredCaseHolds(RuleSet rule) {
    assertThat(failures(ENGINE, rule)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void everySlabBoundaryHasACase(RuleSet rule) {
    List<Integer> terms = rule.cases().stream().map(c -> c.basis().termMonths()).toList();
    for (RuleSet.Slab slab : rule.slabs()) {
      assertThat(terms)
          .as(
              "rule %s needs cases at slab boundaries %d and %d",
              rule.id(), slab.minMonths(), slab.maxMonths())
          .contains(slab.minMonths(), slab.maxMonths());
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void breakdownReplaysToTheQuotedAmount(RuleSet rule) {
    for (RuleSet.RuleCase c : rule.cases()) {
      if (ENGINE.quote(c.basis()) instanceof DutyOutcome.Quoted q) {
        BigDecimal replay =
            q.breakdown().stream()
                .filter(l -> l.kind() == DutyLine.Kind.BASE || l.kind().isDelta())
                .map(DutyLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(replay.movePointRight(2))
            .as("rule %s case '%s' breakdown replay", rule.id(), c.name())
            .isEqualByComparingTo(BigDecimal.valueOf(q.amountPaise()));
      }
    }
  }

  @Test
  void aWrongExpectationIsReportedNamingRuleAndCase() {
    String wrong =
        TestRules.rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\" }",
            """
            cases:
              - { name: deliberately wrong, basis: { termMonths: 12, monthlyRent: "10000" }, expect: { outcome: QUOTED, amountPaise: 100000 } }""");
    RuleSet rule = TestRules.rules(wrong).get(0);
    DutyEngine engine = TestRules.engine(wrong);

    // 1% of 120000 = 1200.00 = 120000 paise, not 100000
    assertThat(failures(engine, rule))
        .singleElement()
        .asString()
        .contains("ZZ-test", "deliberately wrong", "expected 100000", "got 120000");
  }

  /** Every mismatch between a rule's declared cases and the engine, as readable messages. */
  static List<String> failures(DutyEngine engine, RuleSet rule) {
    List<String> failures = new ArrayList<>();
    for (RuleSet.RuleCase c : rule.cases()) {
      String where = "rule " + rule.id() + " case '" + c.name() + "': ";
      DutyOutcome outcome = engine.quote(c.basis());
      RuleSet.OutcomeType actualType =
          switch (outcome) {
            case DutyOutcome.Quoted q -> RuleSet.OutcomeType.QUOTED;
            case DutyOutcome.NeedsAdjudication n -> RuleSet.OutcomeType.NEEDS_ADJUDICATION;
            case DutyOutcome.Unsupported u -> RuleSet.OutcomeType.UNSUPPORTED;
          };
      if (actualType != c.expect().outcome()) {
        failures.add(where + "expected " + c.expect().outcome() + ", got " + outcome);
        continue;
      }
      if (!(outcome instanceof DutyOutcome.Quoted q)) {
        continue;
      }
      if (q.amountPaise() != c.expect().amountPaise()) {
        failures.add(
            where + "expected " + c.expect().amountPaise() + " paise, got " + q.amountPaise());
      }
      for (Map.Entry<String, Long> plan : c.expect().planTotalsPaise().entrySet()) {
        StampPlan actual =
            q.stampPlans().stream()
                .filter(p -> p.mediumId().equals(plan.getKey()))
                .findFirst()
                .orElse(null);
        if (actual == null) {
          failures.add(where + "no plan for medium " + plan.getKey());
        } else if (plan.getValue() == null) {
          if (!(actual.result() instanceof StampPlan.Unplannable)) {
            failures.add(where + "expected " + plan.getKey() + " UNPLANNABLE, got " + actual);
          }
        } else if (!(actual.result() instanceof StampPlan.Planned p)
            || p.totalPaise() != plan.getValue()) {
          failures.add(
              where + "expected " + plan.getKey() + " plan " + plan.getValue() + ", got " + actual);
        }
      }
    }
    return failures;
  }
}

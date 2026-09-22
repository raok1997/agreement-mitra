package in.agreementmitra.rules.duty;

import static in.agreementmitra.rules.duty.TestRules.rule;
import static in.agreementmitra.rules.duty.TestRules.rules;
import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Content hash semantics (design D6) and rule selection by execution date. */
class RuleHasherAndRegistryTest {

  private static final String SLAB =
      "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"%s\" }";

  private static String hashOf(String ruleText) {
    return rules(ruleText).get(0).contentHash();
  }

  @Test
  void rateChangeChangesTheHash() {
    assertThat(hashOf(rule(SLAB.formatted("0.4"), "")))
        .isNotEqualTo(hashOf(rule(SLAB.formatted("0.5"), "")));
  }

  @Test
  void equivalentDecimalsHashTheSame() {
    assertThat(hashOf(rule(SLAB.formatted("0.40"), "")))
        .isEqualTo(hashOf(rule(SLAB.formatted("0.4"), "")));
  }

  @Test
  void casesAndCounselReviewDoNotChangeTheHash() {
    String plain = rule(SLAB.formatted("0.4"), "");
    String annotated =
        rule(
            SLAB.formatted("0.4"),
            """
            counselReview: { reviewer: "someone", reviewedOn: "2026-01-01" }
            cases:
              - { name: c, basis: { termMonths: 1, monthlyRent: "1000" }, expect: { outcome: QUOTED, amountPaise: 400 } }""");

    assertThat(hashOf(annotated)).isEqualTo(hashOf(plain));
  }

  @Test
  void legalReferenceChangeChangesTheHash() {
    String rule = rule(SLAB.formatted("0.4"), "");

    assertThat(hashOf(rule.replace("legalReference: \"test\"", "legalReference: \"Article 2\"")))
        .isNotEqualTo(hashOf(rule));
  }

  @Test
  void aBaseDefaultChangeChangesTheStateHash() {
    String inherited = rule(SLAB.formatted("0.4"), "");
    // Declaring a different value than base is equivalent, for the merged content, to base
    // changing.
    String differentBaseValue =
        rule(SLAB.formatted("0.4"), "rounding: { mode: DOWN, unitRupees: 1 }");
    String sameAsBase = rule(SLAB.formatted("0.4"), "rounding: { mode: UP, unitRupees: 1 }");

    assertThat(hashOf(differentBaseValue)).isNotEqualTo(hashOf(inherited));
    assertThat(hashOf(sameAsBase)).isEqualTo(hashOf(inherited));
  }

  @Test
  void selectsTheVersionInEffectOnTheExecutionDate() {
    List<RuleSet> loaded =
        new RuleSetLoader(
                Map.of(ZzTestExtension.ID, java.util.Set.of(ZzTestExtension.EXCESS_DEPOSIT)))
            .load(RuleSetLoader.resolve(TestRules.DEFAULT_RULE_LOCATIONS));
    RuleSetRegistry registry = new RuleSetRegistry(loaded);

    assertThat(find(registry, "2024-03-31")).isEmpty();
    assertThat(find(registry, "2024-04-01")).hasValue("ZZ-lease-residential-v1");
    assertThat(find(registry, "2025-03-15")).hasValue("ZZ-lease-residential-v1");
    assertThat(find(registry, "2025-03-31")).hasValue("ZZ-lease-residential-v1");
    assertThat(find(registry, "2025-04-01")).hasValue("ZZ-lease-residential-v2");
    assertThat(find(registry, "2099-01-01")).hasValue("ZZ-lease-residential-v2");
  }

  @Test
  void neverSelectsTheNationalDimensionOrAnUnknownState() {
    RuleSetRegistry registry = new RuleSetRegistry(rules(rule(SLAB.formatted("0.4"), "")));

    assertThat(
            registry.find("IN", InstrumentKind.LEASE, Usage.RESIDENTIAL, LocalDate.of(2025, 1, 1)))
        .isEmpty();
    assertThat(
            registry.find("XX", InstrumentKind.LEASE, Usage.RESIDENTIAL, LocalDate.of(2025, 1, 1)))
        .isEmpty();
    assertThat(
            registry.find("ZZ", InstrumentKind.LEASE, Usage.COMMERCIAL, LocalDate.of(2025, 1, 1)))
        .isEmpty();
  }

  private static java.util.Optional<String> find(RuleSetRegistry registry, String date) {
    return registry
        .find("ZZ", InstrumentKind.LEASE, Usage.RESIDENTIAL, LocalDate.parse(date))
        .map(RuleSet::id);
  }
}

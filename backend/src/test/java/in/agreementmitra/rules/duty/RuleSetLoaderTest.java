package in.agreementmitra.rules.duty;

import static in.agreementmitra.rules.duty.TestRules.rule;
import static in.agreementmitra.rules.duty.TestRules.rules;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Loading, base merge and every startup defect (design D3, D7). Each malformed rule is an inline
 * YAML fixture loaded with the real {@code base.yaml}; each failure must name the rule and the
 * defect.
 */
class RuleSetLoaderTest {

  private static final String ONE_SLAB =
      "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"0.5\" }";

  @Test
  void loadsAndInheritsBaseDefaults() {
    RuleSet rule = rules(rule(ONE_SLAB, "")).get(0);

    assertThat(rule.id()).isEqualTo("ZZ-test");
    assertThat(rule.rounding()).isEqualTo(new RuleSet.Rounding(RuleSet.Rounding.Mode.UP, 1));
    assertThat(rule.registration().requiredWhenTermMonthsOver()).isEqualTo(11);
    assertThat(rule.counterpartDuty()).isEqualByComparingTo("0");
    assertThat(rule.surcharges()).isEmpty();
    assertThat(rule.contentHash()).hasSize(64);
  }

  @Test
  void stateRuleOverridesABaseDefault() {
    RuleSet rule =
        rules(rule(ONE_SLAB, "rounding: { mode: HALF_UP, unitRupees: 10 }\ncounterpartDuty: \"5\""))
            .get(0);

    assertThat(rule.rounding()).isEqualTo(new RuleSet.Rounding(RuleSet.Rounding.Mode.HALF_UP, 10));
    assertThat(rule.counterpartDuty()).isEqualByComparingTo("5");
  }

  @Test
  void theShippedAndFixtureRulesLoad() {
    List<RuleSet> loaded =
        new RuleSetLoader(Map.of(ZzTestExtension.ID, Set.of(ZzTestExtension.EXCESS_DEPOSIT)))
            .load(RuleSetLoader.resolve(TestRules.DEFAULT_RULE_LOCATIONS));

    assertThat(loaded)
        .extracting(RuleSet::id)
        .containsExactlyInAnyOrder(
            "ZZ-lease-residential-v1",
            "ZZ-lease-residential-v2",
            "ZZ-lease-commercial",
            "TG-lease-residential",
            "TG-lease-commercial");
  }

  @Test
  void aReviewIsCurrentOnlyWhileItsContentHashMatches() {
    String unreviewed = rule(ONE_SLAB, "");
    String hash = rules(unreviewed).get(0).contentHash();

    RuleSet current =
        rules(rule(ONE_SLAB, "counselReview: { reviewer: counsel, contentHash: \"" + hash + "\" }"))
            .get(0);
    RuleSet stale =
        rules(
                rule(
                    ONE_SLAB.replace("0.5", "0.6"),
                    "counselReview: { contentHash: \"" + hash + "\" }"))
            .get(0);

    assertThat(rules(unreviewed).get(0).ref().reviewed()).isFalse();
    assertThat(current.ref().reviewed()).isTrue();
    assertThat(stale.ref().reviewed()).isFalse();
  }

  @Test
  void slabGapIsNamed() {
    String slabs =
        """
        - { minMonths: 1, maxMonths: 11, consideration: [TOTAL_RENT], ratePercent: "0.5" }
        - { minMonths: 13, maxMonths: 60, consideration: [TOTAL_RENT], ratePercent: "0.5" }""";

    assertDefect(rule(slabs, ""), "slab gap at 12 months");
  }

  @Test
  void slabOverlapIsNamed() {
    String slabs =
        """
        - { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: "0.5" }
        - { minMonths: 12, maxMonths: 60, consideration: [TOTAL_RENT], ratePercent: "0.5" }""";

    assertDefect(rule(slabs, ""), "slabs overlap at 12 months");
  }

  @Test
  void unknownQuantityIsNamed() {
    assertDefect(
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RNT], ratePercent: \"1\" }",
            ""),
        "unknown quantity TOTAL_RNT");
  }

  @Test
  void extensionQuantityIsKnownOnlyWhenTheRuleBindsTheExtension() {
    String slab =
        "- { minMonths: 1, maxMonths: 12, consideration: [EXCESS_DEPOSIT], ratePercent: \"1\" }";
    Map<String, Set<String>> ext = Map.of("zz-test", Set.of("EXCESS_DEPOSIT"));

    assertThat(rules(ext, rule(slab, "extension: zz-test"))).hasSize(1);
    assertThatThrownBy(() -> rules(Map.of(), rule(slab, "")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("unknown quantity EXCESS_DEPOSIT");
  }

  @Test
  void notionalInterestWithoutItsRateParameter() {
    assertDefect(
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [DEPOSIT_NOTIONAL_INTEREST], ratePercent: \"1\" }",
            ""),
        "requires params.depositInterestRatePercent");
  }

  @Test
  void blankLegalReference() {
    assertDefect(
        rule(ONE_SLAB, "").replace("legalReference: \"test\"", "legalReference: \" \""),
        "legalReference is required");
  }

  @Test
  void overlappingEffectiveWindows() {
    String first = rule(ONE_SLAB, "");
    String second = rule(ONE_SLAB, "").replace("id: ZZ-test", "id: ZZ-test-2");

    assertThatThrownBy(() -> rules(first, second))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("rule ZZ-test")
        .hasMessageContaining("effective window overlaps");
  }

  @Test
  void adjacentWindowsDoNotOverlap() {
    String first = rule(ONE_SLAB, "effectiveTo: \"2024-12-31\"");
    String second =
        rule(ONE_SLAB, "")
            .replace("id: ZZ-test", "id: ZZ-test-2")
            .replace("\"2024-01-01\"", "\"2025-01-01\"");

    assertThat(rules(first, second)).hasSize(2);
  }

  @Test
  void nationalStateIsRejected() {
    assertDefect(
        rule(ONE_SLAB, "").replace("state: ZZ", "state: in"),
        "state IN is not a duty jurisdiction");
  }

  @Test
  void nonAbstractRuleWithoutSlabs() {
    assertDefect(rule("[]", "").replace("slabs:\n  []", "slabs: []"), "at least one slab");
  }

  @Test
  void extendsMustBeBase() {
    assertDefect(rule(ONE_SLAB, "").replace("extends: base", "extends: other"), "extends: base");
  }

  @Test
  void unknownExtensionId() {
    assertDefect(rule(ONE_SLAB, "extension: nope"), "extension 'nope' is not a registered");
  }

  @Test
  void extensionBeanNoRuleReferences() {
    assertThatThrownBy(() -> rules(Map.of("orphan", Set.of()), rule(ONE_SLAB, "")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("[orphan]")
        .hasMessageContaining("not referenced by any rule");
  }

  @Test
  void slabNeedsExactlyOneOfRateAndFixedAmount() {
    assertDefect(
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\", fixedAmount: \"5\" }",
            ""),
        "exactly one of ratePercent and fixedAmount");
    assertDefect(
        rule("- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT] }", ""),
        "exactly one of ratePercent and fixedAmount");
  }

  @Test
  void numericMoneyIsRejectedBecauseYamlNumbersAreBinary() {
    assertDefect(
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: 0.1 }", ""),
        "ratePercent must be a quoted decimal string");
  }

  @Test
  void unknownKeyCatchesTypos() {
    assertDefect(
        rule(ONE_SLAB, "minimumAmmount: \"100\""), "unknown key(s) in rule: [minimumAmmount]");
  }

  @Test
  void minimumAboveMaximum() {
    assertDefect(
        rule(ONE_SLAB, "minimumAmount: \"200\"\nmaximumAmount: \"100\""),
        "minimumAmount exceeds maximumAmount");
  }

  @Test
  void quotedCaseRequiresAnAmount() {
    assertDefect(
        rule(
            ONE_SLAB,
            "cases:\n  - { name: c, basis: { termMonths: 1, monthlyRent: \"1\" }, expect: { outcome: QUOTED } }"),
        "amountPaise is required for QUOTED");
  }

  @Test
  void invalidCaseBasisNamesTheCase() {
    assertThatThrownBy(
            () ->
                rules(
                    rule(
                        ONE_SLAB,
                        "cases:\n  - { name: bad, basis: { termMonths: 0 }, expect: { outcome: UNSUPPORTED } }")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("case 'bad'")
        .hasMessageContaining("DutyBasis.termMonths");
  }

  @Test
  void abstractRuleOtherThanBaseOrWithSlabs() {
    assertThatThrownBy(
            () ->
                new RuleSetLoader(Map.of())
                    .load(
                        List.of(
                            TestRules.BASE, TestRules.yaml("b.yaml", "abstract: true\nid: base"))))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("duplicate abstract rule");
    assertThatThrownBy(
            () ->
                new RuleSetLoader(Map.of())
                    .load(List.of(TestRules.yaml("b.yaml", "abstract: true\nid: base\nslabs: []"))))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("unknown key(s) in abstract rule: [slabs]");
  }

  private static void assertDefect(String ruleText, String defect) {
    assertThatThrownBy(() -> rules(ruleText))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("rule ZZ-test")
        .hasMessageContaining(defect);
  }
}

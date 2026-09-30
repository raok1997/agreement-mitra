package in.agreementmitra.rules.duty;

import static in.agreementmitra.rules.duty.TestRules.basis;
import static in.agreementmitra.rules.duty.TestRules.engine;
import static in.agreementmitra.rules.duty.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.rules.DutyLine;
import in.agreementmitra.rules.DutyOutcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Per-slab minimum and maximum (ka-rental-and-commercial-templates, design D1). A rate table can
 * cap one band and leave the rest open -- Karnataka Stamp Act 1957 Sch. Art. 30(1)(i) caps the
 * residential under-one-year band at INR 500 while the 1-10 year band is uncapped -- so the bound
 * must belong to the slab. The test that matters most is {@link
 * #aSlabMaximumDoesNotBoundANeighbouringSlab()}: expressing that cap as the RULE's maximum is the
 * plausible-looking mistake, and it under-stamps every longer term.
 *
 * <p>Amounts in the names are rupees; assertions are paise.
 */
class SlabBoundsTest {

  /** Slab 1 (1-12 months) at 0.5% capped at INR 500; slab 2 (13-60) at 1%, uncapped. */
  private static final String CAPPED_FIRST_SLAB =
      """
      - { minMonths: 1, maxMonths: 12, consideration: [AVERAGE_ANNUAL_RENT], ratePercent: "0.5", maximumAmount: "500" }
      - { minMonths: 13, maxMonths: 60, consideration: [AVERAGE_ANNUAL_RENT], ratePercent: "1" }
      """;

  private static DutyOutcome.Quoted quoted(DutyOutcome outcome) {
    assertThat(outcome).isInstanceOf(DutyOutcome.Quoted.class);
    return (DutyOutcome.Quoted) outcome;
  }

  @Test
  void aSlabMaximumBoundsTheDutyComputedInThatSlab() {
    DutyEngine engine = engine(rule(CAPPED_FIRST_SLAB, ""));

    // 11 months at 20,000 -> average annual rent 240,000 -> 0.5% = 1,200, capped to 500.
    DutyOutcome.Quoted q = quoted(engine.quote(basis(11, "20000").build()));

    assertThat(q.amountPaise()).isEqualTo(50000);
    assertThat(q.breakdown())
        .extracting(DutyLine::kind)
        .containsExactly(
            DutyLine.Kind.QUANTITY,
            DutyLine.Kind.BASE,
            DutyLine.Kind.SLAB_MAXIMUM,
            DutyLine.Kind.ROUNDING);
  }

  @Test
  void theSlabMaximumLineNamesTheSlabItCameFrom() {
    DutyEngine engine = engine(rule(CAPPED_FIRST_SLAB, ""));

    DutyOutcome.Quoted q = quoted(engine.quote(basis(11, "20000").build()));

    // A customer shown INR 500 against a consideration of INR 240,000 is owed the reason, and
    // "this band is capped" is a different fact from "this rule is capped".
    assertThat(q.breakdown())
        .filteredOn(l -> l.kind() == DutyLine.Kind.SLAB_MAXIMUM)
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.label()).contains("500").contains("1-12");
              assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("-700"));
            });
  }

  @Test
  void aSlabMaximumDoesNotBoundANeighbouringSlab() {
    DutyEngine engine = engine(rule(CAPPED_FIRST_SLAB, ""));

    // 13 months at 5,000 -> average annual rent 60,000 -> 1% = 600. If the INR 500 cap were
    // declared
    // as the RULE's maximum this would quote 50000 paise, and the instrument would be
    // under-stamped.
    DutyOutcome.Quoted q = quoted(engine.quote(basis(13, "5000").build()));

    assertThat(q.amountPaise()).isEqualTo(60000);
    assertThat(q.breakdown()).extracting(DutyLine::kind).doesNotContain(DutyLine.Kind.SLAB_MAXIMUM);
  }

  @Test
  void slabBoundsApplyBeforeRuleBounds() {
    DutyEngine engine = engine(rule(CAPPED_FIRST_SLAB, "minimumAmount: \"600\""));

    // The rule bound is the OUTER bound: the slab cap pulls 1,200 down to 500, then the rule
    // minimum
    // lifts it to 600. A slab cap must never be able to defeat a rule minimum.
    DutyOutcome.Quoted q = quoted(engine.quote(basis(11, "20000").build()));

    assertThat(q.amountPaise()).isEqualTo(60000);
    assertThat(q.breakdown())
        .extracting(DutyLine::kind)
        .containsExactly(
            DutyLine.Kind.QUANTITY,
            DutyLine.Kind.BASE,
            DutyLine.Kind.SLAB_MAXIMUM,
            DutyLine.Kind.MINIMUM,
            DutyLine.Kind.ROUNDING);
  }

  /**
   * The symmetric case. No shipped rule declares a slab minimum today -- it exists so the pair
   * matches the rule-level bounds rather than inviting a later asymmetric addition (design D1).
   * This test is its only exercise; do not delete it as covering unused code.
   */
  @Test
  void aSlabMinimumRaisesOnlyItsOwnSlab() {
    String slabs =
        """
        - { minMonths: 1, maxMonths: 12, consideration: [AVERAGE_ANNUAL_RENT], ratePercent: "0.5", minimumAmount: "1000" }
        - { minMonths: 13, maxMonths: 60, consideration: [AVERAGE_ANNUAL_RENT], ratePercent: "1" }
        """;
    DutyEngine engine = engine(rule(slabs, ""));

    // 11 months at 5,000 -> average annual rent 60,000 -> 0.5% = 300, raised to the slab minimum.
    DutyOutcome.Quoted inSlab = quoted(engine.quote(basis(11, "5000").build()));
    assertThat(inSlab.amountPaise()).isEqualTo(100000);
    assertThat(inSlab.breakdown()).extracting(DutyLine::kind).contains(DutyLine.Kind.SLAB_MINIMUM);

    // The neighbouring slab computes 600 and is left alone.
    DutyOutcome.Quoted nextSlab = quoted(engine.quote(basis(13, "5000").build()));
    assertThat(nextSlab.amountPaise()).isEqualTo(60000);
    assertThat(nextSlab.breakdown())
        .extracting(DutyLine::kind)
        .doesNotContain(DutyLine.Kind.SLAB_MINIMUM);
  }

  @Test
  void surchargeAndCounterpartApplyToTheSlabBoundedDuty() {
    DutyEngine engine =
        engine(
            rule(
                CAPPED_FIRST_SLAB,
                """
                counterpartDuty: "50"
                surcharges: [ { name: cess, percentOfDuty: "10" } ]
                """));

    // 1,200 -> slab cap 500 -> +10% cess (50) -> +1 counterpart (50) = 600. The cap bounds the
    // slab's duty, not the quote: everything after it is added on top.
    DutyOutcome.Quoted q = quoted(engine.quote(basis(11, "20000").counterparts(2).build()));

    assertThat(q.amountPaise()).isEqualTo(60000);
    assertThat(q.breakdown())
        .extracting(DutyLine::kind)
        .containsExactly(
            DutyLine.Kind.QUANTITY,
            DutyLine.Kind.BASE,
            DutyLine.Kind.SLAB_MAXIMUM,
            DutyLine.Kind.SURCHARGE,
            DutyLine.Kind.COUNTERPART,
            DutyLine.Kind.ROUNDING);
  }

  @Test
  void nothingIsRoundedBeforeTheSlabBoundIsApplied() {
    String slabs =
        """
        - { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: "1", maximumAmount: "100.004" }
        """;
    DutyEngine engine = engine(rule(slabs, ""));

    // 1% of 20,000 = 200, capped at 100.004, rounded UP once at the end to 101.
    assertThat(quoted(engine.quote(basis(1, "20000").build())).amountPaise()).isEqualTo(10100);
  }

  @Test
  void aNegativeSlabBoundIsRejectedAtLoad() {
    assertThatThrownBy(
            () ->
                TestRules.rules(
                    rule(
                        "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT],"
                            + " ratePercent: \"1\", maximumAmount: \"-1\" }",
                        "")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("maximumAmount")
        .hasMessageContaining("must not be negative");
  }

  @Test
  void aSlabMinimumAboveItsSlabMaximumIsRejectedAtLoad() {
    assertThatThrownBy(
            () ->
                TestRules.rules(
                    rule(
                        "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT],"
                            + " ratePercent: \"1\", minimumAmount: \"900\", maximumAmount: \"100\" }",
                        "")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("slab 1-12")
        .hasMessageContaining("minimumAmount exceeds maximumAmount");
  }

  @Test
  void aSlabBoundOnAFixedAmountSlabIsRejectedAtLoad() {
    // A fixed amount IS the duty, so bounding it means the rule file contradicts itself. Honouring
    // the narrower of the two would hide the contradiction behind a plausible number.
    assertThatThrownBy(
            () ->
                TestRules.rules(
                    rule(
                        "- { minMonths: 1, maxMonths: 12, fixedAmount: \"800\","
                            + " maximumAmount: \"500\" }",
                        "")))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("slab 1-12")
        .hasMessageContaining("fixedAmount");
  }

  @Test
  void aSlabBoundChangesTheRuleContentHash() {
    // The counsel review pins a content hash. Relaxing a cap must invalidate that review, or a
    // rule that now charges twice as much would still read as reviewed.
    String uncapped =
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\" }",
            "");
    String capped =
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\","
                + " maximumAmount: \"500\" }",
            "");

    assertThat(TestRules.rules(capped).get(0).contentHash())
        .isNotEqualTo(TestRules.rules(uncapped).get(0).contentHash());
  }

  @Test
  void theShippedTelanganaRulesDeclareNoSlabBounds() {
    // By construction their duty cannot have moved: every Telangana slab carries Bounds.NONE, so
    // the
    // new step is a no-op for them. Asserted rather than assumed, because it is the whole reason
    // this change could add a step to a pipeline every rule flows through.
    assertThat(
            new RuleSetLoader(
                    java.util.Map.of(
                        ZzTestExtension.ID, new ZzTestExtension().providedQuantities()))
                .load(RuleSetLoader.resolve(TestRules.DEFAULT_RULE_LOCATIONS)))
        .filteredOn(r -> r.state().equals("TG"))
        .isNotEmpty()
        .allSatisfy(
            r -> assertThat(r.slabs()).allSatisfy(s -> assertThat(s.bounds().isEmpty()).isTrue()));
  }
}

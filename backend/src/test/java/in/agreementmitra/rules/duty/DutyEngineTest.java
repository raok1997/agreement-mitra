package in.agreementmitra.rules.duty;

import static in.agreementmitra.rules.duty.TestRules.basis;
import static in.agreementmitra.rules.duty.TestRules.engine;
import static in.agreementmitra.rules.duty.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyLine;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.StampPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The fixed calculation pipeline (design D4) against inline rules. No Spring context. Amounts in
 * the scenario names are rupees; assertions are paise.
 */
class DutyEngineTest {

  private static DutyOutcome.Quoted quoted(DutyOutcome outcome) {
    assertThat(outcome).isInstanceOf(DutyOutcome.Quoted.class);
    return (DutyOutcome.Quoted) outcome;
  }

  @Test
  void onlyReviewedRulesAreChargeableUnlessUnreviewedAreAllowed() {
    String ruleText = rule("- { minMonths: 1, maxMonths: 12, fixedAmount: \"100\" }", "");
    RuleSetRegistry registry = new RuleSetRegistry(TestRules.rules(ruleText));
    DutyEngine strict =
        new DutyEngine(registry, List.of(), List.of(), new StampPaperPlanner(), false);
    DutyEngine lenient =
        new DutyEngine(registry, List.of(), List.of(), new StampPaperPlanner(), true);
    in.agreementmitra.rules.RuleRef ref = registry.all().get(0).ref();

    assertThat(strict.isChargeable(ref)).isFalse();
    assertThat(strict.chargeableStates()).isEmpty();
    assertThat(lenient.isChargeable(ref)).isTrue();
    assertThat(lenient.chargeableStates()).containsExactly("ZZ");
    assertThat(strict.isChargeable(new in.agreementmitra.rules.RuleRef("x", "y", "z", true)))
        .isTrue();
  }

  @Test
  void minimumAppliesBeforeRounding() {
    DutyEngine engine =
        engine(
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"0.4\" }",
                "minimumAmount: \"100\""));

    DutyOutcome.Quoted q = quoted(engine.quote(basis(1, "20000").build()));

    assertThat(q.amountPaise()).isEqualTo(10000);
    assertThat(q.breakdown())
        .extracting(DutyLine::kind)
        .containsExactly(
            DutyLine.Kind.QUANTITY,
            DutyLine.Kind.BASE,
            DutyLine.Kind.MINIMUM,
            DutyLine.Kind.ROUNDING);
  }

  @Test
  void surchargeIsComputedOnBoundedDuty() {
    DutyEngine engine =
        engine(
            rule(
                "- { minMonths: 1, maxMonths: 12, fixedAmount: \"5000\" }",
                "maximumAmount: \"2000\"\nsurcharges: [ { name: cess, percentOfDuty: \"10\" } ]"));

    assertThat(quoted(engine.quote(basis(1, "1").build())).amountPaise()).isEqualTo(220000);
  }

  @Test
  void roundingHappensOnceAtTheEnd() {
    DutyEngine engine =
        engine(
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\" }",
                ""));

    // 1% of 10000.40 = 100.004 -> UP to the whole rupee
    assertThat(quoted(engine.quote(basis(1, "10000.40").build())).amountPaise()).isEqualTo(10100);
  }

  @Test
  void slabBoundariesAndATermBeyondEverySlab() {
    DutyEngine engine =
        engine(
            rule(
                """
                - { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: "1" }
                - { minMonths: 13, maxMonths: 60, consideration: [TOTAL_RENT], ratePercent: "2" }""",
                ""));

    assertThat(quoted(engine.quote(basis(12, "1000").build())).amountPaise()).isEqualTo(12000);
    assertThat(quoted(engine.quote(basis(13, "1000").build())).amountPaise()).isEqualTo(26000);
    assertThat(engine.quote(basis(61, "1000").build()))
        .isInstanceOfSatisfying(
            DutyOutcome.Unsupported.class, u -> assertThat(u.reason()).contains("61 months"));
  }

  @ParameterizedTest(name = "{0} to {1} rupee(s) -> {2} paise")
  @CsvSource({
    "UP, 1, 12400",
    "HALF_UP, 1, 12300",
    "DOWN, 1, 12300",
    "UP, 10, 13000",
    "HALF_UP, 10, 12000",
    "DOWN, 100, 10000"
  })
  void roundingModesAndUnits(String mode, int unit, long expectedPaise) {
    DutyEngine engine =
        engine(
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"1\" }",
                "rounding: { mode: %s, unitRupees: %d }".formatted(mode, unit)));

    // 1% of 12345 = 123.45
    assertThat(quoted(engine.quote(basis(1, "12345").build())).amountPaise())
        .isEqualTo(expectedPaise);
  }

  @Test
  void counterpartDutyAppliesToCopiesBeyondTheOriginal() {
    DutyEngine engine =
        engine(
            rule(
                "- { minMonths: 1, maxMonths: 12, fixedAmount: \"100\" }",
                "counterpartDuty: \"50\""));

    assertThat(quoted(engine.quote(basis(1, "1").build())).amountPaise()).isEqualTo(10000);
    assertThat(quoted(engine.quote(basis(1, "1").counterparts(3).build())).amountPaise())
        .isEqualTo(20000);
  }

  @Test
  void registrationFlagDoesNotChangeTheAmount() {
    String slab =
        "- { minMonths: 1, maxMonths: 24, consideration: [TOTAL_RENT], ratePercent: \"1\" }";
    DutyOutcome.Quoted withThreshold =
        quoted(engine(rule(slab, "")).quote(basis(12, "1000").build()));
    DutyOutcome.Quoted noThreshold =
        quoted(engine(rule(slab, "registration: null")).quote(basis(12, "1000").build()));

    assertThat(withThreshold.registrationRequired()).isTrue();
    assertThat(noThreshold.registrationRequired()).isFalse();
    assertThat(withThreshold.amountPaise()).isEqualTo(noThreshold.amountPaise());
    assertThat(
            quoted(engine(rule(slab, "")).quote(basis(11, "1000").build())).registrationRequired())
        .isFalse();
  }

  @Test
  void extensionRefusalGivesNeedsAdjudication() {
    DutyEngine engine =
        engine(
            List.of(new ZzTestExtension()),
            TestRules.ANY_AMOUNT_CATALOG,
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [EXCESS_DEPOSIT], ratePercent: \"1\" }",
                "extension: zz-test\nparams: { depositMonthsCap: \"3\" }"));

    DutyOutcome outcome =
        engine.quote(basis(11, "1000").nonRefundableDeposit(BigDecimal.ONE).build());

    assertThat(outcome)
        .isInstanceOfSatisfying(
            DutyOutcome.NeedsAdjudication.class,
            n -> assertThat(n.rule().id()).isEqualTo("ZZ-test"));
  }

  @Test
  void extensionQuantityAndAdjustmentAppearAsBreakdownLines() {
    DutyEngine engine =
        engine(
            List.of(new ZzTestExtension()),
            TestRules.ANY_AMOUNT_CATALOG,
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [EXCESS_DEPOSIT], ratePercent: \"1\" }",
                "extension: zz-test\nparams: { depositMonthsCap: \"3\" }"));

    DutyOutcome.Quoted q =
        quoted(engine.quote(basis(11, "1000").refundableDeposit(new BigDecimal("13000")).build()));

    // excess deposit 10000 -> 1% = 100 -> +25 by the extension
    assertThat(q.amountPaise()).isEqualTo(12500);
    assertThat(q.breakdown())
        .anySatisfy(
            l -> {
              assertThat(l.kind()).isEqualTo(DutyLine.Kind.QUANTITY);
              assertThat(l.label()).isEqualTo("EXCESS_DEPOSIT");
              assertThat(l.amount()).isEqualByComparingTo("10000");
            })
        .anySatisfy(
            l -> {
              assertThat(l.kind()).isEqualTo(DutyLine.Kind.EXTENSION);
              assertThat(l.amount()).isEqualByComparingTo("25");
            });
  }

  @Test
  void extensionReturningAnUndeclaredQuantityFails() {
    DutyExtension liar =
        new DutyExtension() {
          @Override
          public String id() {
            return "liar";
          }

          @Override
          public Set<String> providedQuantities() {
            return Set.of("DECLARED");
          }

          @Override
          public Quantities extraQuantities(Quantities q, DutyBasis b, RuleSet r) {
            return q.with("DECLARED", BigDecimal.ONE).with("UNDECLARED", BigDecimal.ONE);
          }
        };
    DutyEngine engine =
        engine(
            List.of(liar),
            TestRules.ANY_AMOUNT_CATALOG,
            rule(
                "- { minMonths: 1, maxMonths: 12, consideration: [DECLARED], ratePercent: \"1\" }",
                "extension: liar"));

    assertThatThrownBy(() -> engine.quote(basis(1, "1").build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UNDECLARED");
  }

  @Test
  void failsClosedForNationalUnknownStateAndDatesOutsideEveryWindow() {
    DutyEngine engine = engine(rule("- { minMonths: 1, maxMonths: 12, fixedAmount: \"100\" }", ""));

    assertThat(engine.quote(withState(basis(1, "1").build(), "IN")))
        .isInstanceOf(DutyOutcome.Unsupported.class);
    assertThat(engine.quote(withState(basis(1, "1").build(), "XX")))
        .isInstanceOf(DutyOutcome.Unsupported.class);
    DutyBasis beforeWindow =
        DutyBasis.builder(
                "ZZ",
                DutyBasis.InstrumentKind.LEASE,
                DutyBasis.Usage.RESIDENTIAL,
                LocalDate.of(2023, 12, 31),
                1,
                BigDecimal.ONE)
            .build();
    assertThat(engine.quote(beforeWindow)).isInstanceOf(DutyOutcome.Unsupported.class);
  }

  @Test
  void aRuleWithNoCatalogInEffectIsUnsupported() {
    String otherState = TestRules.ANY_AMOUNT_CATALOG.replace("state: ZZ", "state: ZY");
    DutyEngine engine =
        engine(
            List.of(),
            otherState,
            rule("- { minMonths: 1, maxMonths: 12, fixedAmount: \"100\" }", ""));

    assertThat(engine.quote(basis(1, "1").build()))
        .isInstanceOfSatisfying(
            DutyOutcome.Unsupported.class,
            u -> assertThat(u.reason()).contains("no stamp paper catalog"));
  }

  @Test
  void planningNeverChangesTheLegalDuty() {
    String ruleText =
        rule(
            "- { minMonths: 1, maxMonths: 12, consideration: [TOTAL_RENT], ratePercent: \"0.4\" }",
            "");
    String physical =
        """
        state: ZZ
        effectiveFrom: "2000-01-01"
        source: "test"
        media:
          - { id: physical, kind: DENOMINATIONS, denominations: ["100", "500"], maxPapers: 2 }
        """;

    DutyOutcome.Quoted planned =
        quoted(engine(List.of(), physical, ruleText).quote(basis(12, "10000").build()));
    DutyOutcome.Quoted exact = quoted(engine(ruleText).quote(basis(12, "10000").build()));

    // 0.4% of 120000 = 480 -> physical plan 500
    assertThat(planned.amountPaise()).isEqualTo(exact.amountPaise()).isEqualTo(48000);
    assertThat(planned.stampPlans())
        .singleElement()
        .satisfies(p -> assertThat(((StampPlan.Planned) p.result()).totalPaise()).isEqualTo(50000));
    assertThat(planned.catalog().state()).isEqualTo("ZZ");
  }

  private static DutyBasis withState(DutyBasis b, String state) {
    return new DutyBasis(
        state,
        b.instrumentKind(),
        b.usage(),
        b.executionDate(),
        b.termMonths(),
        b.monthlyRent(),
        b.escalationPercent(),
        b.escalationEveryMonths(),
        b.rentFreeMonths(),
        b.refundableDeposit(),
        b.nonRefundableDeposit(),
        b.advanceRent(),
        b.premium(),
        b.counterparts());
  }
}

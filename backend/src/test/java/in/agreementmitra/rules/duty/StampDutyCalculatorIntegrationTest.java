package in.agreementmitra.rules.duty;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.StampDutyCalculator;
import in.agreementmitra.rules.StampPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Real Spring wiring of the calculator: {@link StampDutyConfiguration} with classpath rule and
 * catalog discovery and extension beans. No containers -- the module has no infrastructure.
 */
class StampDutyCalculatorIntegrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(StampDutyConfiguration.class, ZzTestExtension.class);

  private static DutyBasis zzResidential(String state, int term, String rent, String deposit) {
    return DutyBasis.builder(
            state,
            DutyBasis.InstrumentKind.LEASE,
            DutyBasis.Usage.RESIDENTIAL,
            LocalDate.of(2025, 6, 1),
            term,
            new BigDecimal(rent))
        .refundableDeposit(new BigDecimal(deposit))
        .build();
  }

  @Test
  void quotesEndToEndWithStampPlansForEveryMedium() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);

          DutyOutcome outcome = calculator.quote(zzResidential("zz", 12, "10000", "30000"));

          assertThat(outcome)
              .isInstanceOfSatisfying(
                  DutyOutcome.Quoted.class,
                  q -> {
                    assertThat(q.amountPaise()).isEqualTo(66000);
                    assertThat(q.rule().id()).isEqualTo("ZZ-lease-residential-v2");
                    assertThat(q.rule().contentHash()).hasSize(64);
                    assertThat(q.registrationRequired()).isTrue();
                    assertThat(q.stampPlans())
                        .extracting(StampPlan::mediumId)
                        .containsExactly("physical", "e-stamp");
                  });
        });
  }

  @Test
  void nationalAndUnknownStatesAreUnsupported() {
    runner.run(
        context -> {
          StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);

          assertThat(calculator.quote(zzResidential("IN", 11, "10000", "0")))
              .isInstanceOf(DutyOutcome.Unsupported.class);
          assertThat(calculator.quote(zzResidential("XX", 11, "10000", "0")))
              .isInstanceOf(DutyOutcome.Unsupported.class);
        });
  }

  @Test
  void malformedRuleDataFailsContextStartupNamingTheRule() {
    runner
        .withPropertyValues(
            "rules.stamp-duty.locations=classpath*:rules/stamp-duty/base.yaml,"
                + "classpath*:rules/stamp-duty-invalid/*.yaml")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(RuleSetDefinitionException.class)
                  .hasMessageContaining("rule ZZ-invalid")
                  .hasMessageContaining("slab gap at 12 months");
            });
  }

  @Test
  void shippedTelanganaRulesQuoteAndAreNotChargeableByDefault() {
    // Only what main resources ship: base + TG rules + the TG catalog, no test extension bean.
    ApplicationContextRunner shipped =
        new ApplicationContextRunner()
            .withUserConfiguration(StampDutyConfiguration.class)
            .withPropertyValues(
                "rules.stamp-duty.locations=classpath*:rules/stamp-duty/base.yaml,"
                    + "classpath*:rules/stamp-duty/TG/*.yaml",
                "rules.stamp-paper.locations=classpath*:rules/stamp-paper/TG.yaml");

    shipped.run(
        context -> {
          assertThat(context).hasNotFailed();
          StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);
          DutyOutcome outcome = calculator.quote(zzResidential("TG", 11, "15000", "45000"));

          assertThat(outcome)
              .isInstanceOfSatisfying(
                  DutyOutcome.Quoted.class,
                  q -> {
                    assertThat(q.amountPaise()).isEqualTo(84000);
                    assertThat(q.rule().reviewed()).isFalse();
                    assertThat(calculator.isChargeable(q.rule())).isFalse();
                    assertThat(q.stampPlans())
                        .extracting(StampPlan::mediumId)
                        .containsExactly("stamp-paper", "challan");
                  });
          assertThat(calculator.chargeableStates()).isEmpty();
          assertThat(calculator.quote(zzResidential("ZZ", 11, "10000", "0")))
              .isInstanceOf(DutyOutcome.Unsupported.class);
        });
    shipped
        .withPropertyValues("rules.stamp-duty.allow-unreviewed=true")
        .run(
            context ->
                assertThat(context.getBean(StampDutyCalculator.class).chargeableStates())
                    .containsExactly("TG"));
  }

  /** Only what main resources ship for Karnataka: base + KA rules + the KA catalog. */
  private static ApplicationContextRunner shippedKarnataka() {
    return new ApplicationContextRunner()
        .withUserConfiguration(StampDutyConfiguration.class)
        .withPropertyValues(
            "rules.stamp-duty.locations=classpath*:rules/stamp-duty/base.yaml,"
                + "classpath*:rules/stamp-duty/KA/*.yaml",
            "rules.stamp-paper.locations=classpath*:rules/stamp-paper/KA.yaml");
  }

  private static DutyBasis karnataka(DutyBasis.Usage usage, int term, String rent, String deposit) {
    return DutyBasis.builder(
            "KA",
            DutyBasis.InstrumentKind.LEASE,
            usage,
            LocalDate.of(2025, 6, 1),
            term,
            new BigDecimal(rent))
        .refundableDeposit(new BigDecimal(deposit))
        .build();
  }

  @Test
  void shippedKarnatakaRulesQuoteAndAreNotChargeableByDefault() {
    shippedKarnataka()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);

              // 11 months at 20,000 with a 100,000 deposit: average annual rent 240,000 + 100,000 =
              // 340,000, 0.5% = 1,700, capped by Art. 30(1)(i) to 500.
              assertThat(
                      calculator.quote(
                          karnataka(DutyBasis.Usage.RESIDENTIAL, 11, "20000", "100000")))
                  .isInstanceOfSatisfying(
                      DutyOutcome.Quoted.class,
                      q -> {
                        assertThat(q.amountPaise()).isEqualTo(50000);
                        assertThat(q.rule().id()).isEqualTo("KA-lease-residential");
                        assertThat(q.rule().reviewed()).isFalse();
                        assertThat(calculator.isChargeable(q.rule())).isFalse();
                      });
              assertThat(calculator.chargeableStates()).isEmpty();
            });

    shippedKarnataka()
        .withPropertyValues("rules.stamp-duty.allow-unreviewed=true")
        .run(
            context ->
                assertThat(context.getBean(StampDutyCalculator.class).chargeableStates())
                    .containsExactly("KA"));
  }

  @Test
  void theKarnatakaCatalogPlansTheExactDutyAndOffersLowerPapers() {
    shippedKarnataka()
        .run(
            context -> {
              StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);

              // Commercial at 12 months is uncapped: 0.5% of 340,000 = 1,700. No single Telangana
              // -style paper reaches that; Karnataka's any-amount e-stamp plans it exactly, which
              // is
              // the whole reason Karnataka does not use the single-papers offer policy.
              assertThat(
                      calculator.quote(
                          karnataka(DutyBasis.Usage.COMMERCIAL, 12, "20000", "100000")))
                  .isInstanceOfSatisfying(
                      DutyOutcome.Quoted.class,
                      q -> {
                        assertThat(q.amountPaise()).isEqualTo(170000);
                        assertThat(q.stampPlans())
                            .extracting(StampPlan::mediumId)
                            .containsExactly("e-stamp", "stamp-paper");
                        StampPlan eStamp = q.stampPlans().get(0);
                        assertThat(eStamp.result())
                            .isInstanceOfSatisfying(
                                StampPlan.Planned.class,
                                p -> {
                                  assertThat(p.totalPaise()).isEqualTo(170000);
                                  assertThat(p.excessPaise()).isZero();
                                });
                        // The denominations medium is what supplies the below-duty override options
                        // the customer may choose instead; without it Karnataka would offer one
                        // value and no override.
                        assertThat(q.stampPlans().get(1).denominationsPaise())
                            .contains(50000L, 20000L, 10000L, 5000L, 2000L);
                      });
            });
  }

  @Test
  void karnatakaReportsRegistrationOnlyAboveTwelveMonths() {
    shippedKarnataka()
        .run(
            context -> {
              StampDutyCalculator calculator = context.getBean(StampDutyCalculator.class);

              // Registration Act 1908 s.17(1)(d), unamended in Karnataka -- unlike Telangana, where
              // a state amendment makes every lease registrable.
              assertThat(calculator.quote(karnataka(DutyBasis.Usage.RESIDENTIAL, 12, "5000", "0")))
                  .isInstanceOfSatisfying(
                      DutyOutcome.Quoted.class,
                      q -> assertThat(q.registrationRequired()).isFalse());
              assertThat(calculator.quote(karnataka(DutyBasis.Usage.RESIDENTIAL, 13, "5000", "0")))
                  .isInstanceOfSatisfying(
                      DutyOutcome.Quoted.class, q -> assertThat(q.registrationRequired()).isTrue());
            });
  }
}

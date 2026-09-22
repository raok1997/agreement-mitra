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
}

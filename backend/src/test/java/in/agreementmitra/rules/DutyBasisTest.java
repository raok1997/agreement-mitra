package in.agreementmitra.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Validation and normalization of the calculator's input facts. Pure unit tests. */
class DutyBasisTest {

  private static DutyBasis.Builder valid() {
    return DutyBasis.builder(
        "zz",
        InstrumentKind.LEASE,
        Usage.RESIDENTIAL,
        LocalDate.of(2025, 1, 1),
        11,
        BigDecimal.TEN);
  }

  @Test
  void normalizesStateAndDefaultsOptionalAmountsToZero() {
    DutyBasis basis =
        DutyBasis.builder(
                " tg ",
                InstrumentKind.LEASE,
                Usage.RESIDENTIAL,
                LocalDate.of(2025, 1, 1),
                11,
                BigDecimal.TEN)
            .refundableDeposit(null)
            .build();

    assertThat(basis.dutyState()).isEqualTo("TG");
    assertThat(basis.refundableDeposit()).isEqualByComparingTo("0");
    assertThat(basis.premium()).isEqualByComparingTo("0");
    assertThat(basis.counterparts()).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "monthlyRent, rent",
    "refundableDeposit, deposit",
    "nonRefundableDeposit, nonRefundable",
    "advanceRent, advance",
    "premium, premium",
    "escalationPercent, escalation"
  })
  void negativeMoneyIsRejectedNamingTheField(String field, String setter) {
    UnaryOperator<DutyBasis.Builder> negative =
        switch (setter) {
          case "rent" ->
              b ->
                  DutyBasis.builder(
                      "ZZ",
                      InstrumentKind.LEASE,
                      Usage.RESIDENTIAL,
                      LocalDate.of(2025, 1, 1),
                      11,
                      new BigDecimal("-1"));
          case "deposit" -> b -> b.refundableDeposit(new BigDecimal("-1"));
          case "nonRefundable" -> b -> b.nonRefundableDeposit(new BigDecimal("-1"));
          case "advance" -> b -> b.advanceRent(new BigDecimal("-1"));
          case "premium" -> b -> b.premium(new BigDecimal("-1"));
          default -> b -> b.escalation(new BigDecimal("-1"), 12);
        };

    assertThatThrownBy(() -> negative.apply(valid()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DutyBasis." + field);
  }

  @Test
  void structuralFieldsAreValidatedByName() {
    assertThatThrownBy(
            () ->
                DutyBasis.builder(
                        " ", InstrumentKind.LEASE, Usage.RESIDENTIAL, LocalDate.now(), 11, null)
                    .build())
        .hasMessageContaining("DutyBasis.dutyState");
    assertThatThrownBy(
            () ->
                DutyBasis.builder("ZZ", null, Usage.RESIDENTIAL, LocalDate.now(), 11, null).build())
        .hasMessageContaining("DutyBasis.instrumentKind");
    assertThatThrownBy(
            () ->
                DutyBasis.builder("ZZ", InstrumentKind.LEASE, null, LocalDate.now(), 11, null)
                    .build())
        .hasMessageContaining("DutyBasis.usage");
    assertThatThrownBy(
            () ->
                DutyBasis.builder("ZZ", InstrumentKind.LEASE, Usage.RESIDENTIAL, null, 11, null)
                    .build())
        .hasMessageContaining("DutyBasis.executionDate");
    assertThatThrownBy(
            () ->
                DutyBasis.builder(
                        "ZZ", InstrumentKind.LEASE, Usage.RESIDENTIAL, LocalDate.now(), 0, null)
                    .build())
        .hasMessageContaining("DutyBasis.termMonths");
    assertThatThrownBy(() -> valid().rentFreeMonths(12).build())
        .hasMessageContaining("DutyBasis.rentFreeMonths");
    assertThatThrownBy(() -> valid().escalation(BigDecimal.ONE, -1).build())
        .hasMessageContaining("DutyBasis.escalationEveryMonths");
    assertThatThrownBy(() -> valid().counterparts(0).build())
        .hasMessageContaining("DutyBasis.counterparts");
  }
}

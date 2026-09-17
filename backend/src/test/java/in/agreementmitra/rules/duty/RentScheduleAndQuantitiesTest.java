package in.agreementmitra.rules.duty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rent derived from the month-by-month schedule, and the standard named quantities. */
class RentScheduleAndQuantitiesTest {

  @Test
  void flatRent() {
    DutyBasis basis = TestRules.basis(11, "15000").build();

    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("165000");
    assertThat(RentSchedule.averageAnnualRent(basis)).isEqualByComparingTo("180000");
  }

  @Test
  void escalationIsAppliedAtEachIntervalNotApproximated() {
    DutyBasis basis = TestRules.basis(24, "10000").escalation(new BigDecimal("10"), 12).build();

    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("252000.00");
    assertThat(RentSchedule.averageAnnualRent(basis)).isEqualByComparingTo("126000.00");
  }

  @Test
  void escalationCompounds() {
    DutyBasis basis = TestRules.basis(60, "10000").escalation(new BigDecimal("10"), 12).build();

    // 12 x (10000 + 11000 + 12100 + 13310 + 14641)
    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("732612");
  }

  @Test
  void rentFreeMonthsAreExcluded() {
    DutyBasis basis = TestRules.basis(11, "10000").rentFreeMonths(1).build();

    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("100000.00");
  }

  @Test
  void escalationIntervalLongerThanTermNeverApplies() {
    DutyBasis basis = TestRules.basis(11, "10000").escalation(new BigDecimal("10"), 12).build();

    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("110000");
  }

  @Test
  void zeroRent() {
    DutyBasis basis = TestRules.basis(11, "0").build();

    assertThat(RentSchedule.totalRent(basis)).isEqualByComparingTo("0");
    assertThat(RentSchedule.averageAnnualRent(basis)).isEqualByComparingTo("0");
  }

  @Test
  void averageAnnualRentOfANonWholeYearTermIsExactEnough() {
    DutyBasis basis = TestRules.basis(13, "10000").build();

    assertThat(RentSchedule.averageAnnualRent(basis)).isEqualByComparingTo("120000");
  }

  @Test
  void depositNotionalInterestUsesTheRuleRateAndTerm() {
    DutyBasis basis =
        TestRules.basis(24, "10000").refundableDeposit(new BigDecimal("60000")).build();

    Quantities q =
        Quantities.standard(
            basis, Map.of(Quantities.DEPOSIT_INTEREST_RATE_PARAM, new BigDecimal("10")));

    // 60000 x 10% x 2 years
    assertThat(q.get(Quantities.DEPOSIT_NOTIONAL_INTEREST)).isEqualByComparingTo("12000");
    assertThat(q.get(Quantities.REFUNDABLE_DEPOSIT)).isEqualByComparingTo("60000");
    assertThat(q.names()).containsExactlyInAnyOrderElementsOf(Quantities.STANDARD_NAMES);
  }

  @Test
  void depositNotionalInterestWithoutItsParameterIsNotAvailable() {
    Quantities q = Quantities.standard(TestRules.basis(24, "10000").build(), Map.of());

    assertThatThrownBy(() -> q.get(Quantities.DEPOSIT_NOTIONAL_INTEREST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Quantities.DEPOSIT_NOTIONAL_INTEREST);
  }

  @Test
  void withAddsANameWithoutMutating() {
    Quantities q = Quantities.standard(TestRules.basis(1, "1").build(), Map.of());
    Quantities more = q.with("EXTRA", BigDecimal.ONE);

    assertThat(more.get("EXTRA")).isEqualByComparingTo("1");
    assertThatThrownBy(() -> q.get("EXTRA")).isInstanceOf(IllegalArgumentException.class);
  }
}

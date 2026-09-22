package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The named amounts a slab's consideration may sum. Immutable; a state extension adds names with
 * {@link #with}.
 */
final class Quantities {

  static final String TOTAL_RENT = "TOTAL_RENT";
  static final String AVERAGE_ANNUAL_RENT = "AVERAGE_ANNUAL_RENT";
  static final String MONTHLY_RENT = "MONTHLY_RENT";
  static final String REFUNDABLE_DEPOSIT = "REFUNDABLE_DEPOSIT";
  static final String NON_REFUNDABLE_DEPOSIT = "NON_REFUNDABLE_DEPOSIT";
  static final String ADVANCE_RENT = "ADVANCE_RENT";
  static final String PREMIUM = "PREMIUM";
  static final String DEPOSIT_NOTIONAL_INTEREST = "DEPOSIT_NOTIONAL_INTEREST";

  /** Rule parameter {@link #DEPOSIT_NOTIONAL_INTEREST} requires. */
  static final String DEPOSIT_INTEREST_RATE_PARAM = "depositInterestRatePercent";

  static final Set<String> STANDARD_NAMES =
      Set.of(
          TOTAL_RENT,
          AVERAGE_ANNUAL_RENT,
          MONTHLY_RENT,
          REFUNDABLE_DEPOSIT,
          NON_REFUNDABLE_DEPOSIT,
          ADVANCE_RENT,
          PREMIUM,
          DEPOSIT_NOTIONAL_INTEREST);

  private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final Map<String, BigDecimal> values;

  private Quantities(Map<String, BigDecimal> values) {
    this.values = Map.copyOf(values);
  }

  /**
   * The standard quantities for a basis. {@link #DEPOSIT_NOTIONAL_INTEREST} is present only when
   * the rule supplies its rate parameter.
   */
  static Quantities standard(DutyBasis basis, Map<String, BigDecimal> params) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    values.put(TOTAL_RENT, RentSchedule.totalRent(basis));
    values.put(AVERAGE_ANNUAL_RENT, RentSchedule.averageAnnualRent(basis));
    values.put(MONTHLY_RENT, basis.monthlyRent());
    values.put(REFUNDABLE_DEPOSIT, basis.refundableDeposit());
    values.put(NON_REFUNDABLE_DEPOSIT, basis.nonRefundableDeposit());
    values.put(ADVANCE_RENT, basis.advanceRent());
    values.put(PREMIUM, basis.premium());
    BigDecimal rate = params.get(DEPOSIT_INTEREST_RATE_PARAM);
    if (rate != null) {
      // deposit x rate% per annum x term in years
      values.put(
          DEPOSIT_NOTIONAL_INTEREST,
          basis
              .refundableDeposit()
              .multiply(rate)
              .multiply(BigDecimal.valueOf(basis.termMonths()))
              .divide(HUNDRED.multiply(TWELVE), MathContext.DECIMAL128));
    }
    return new Quantities(values);
  }

  Quantities with(String name, BigDecimal value) {
    Map<String, BigDecimal> next = new LinkedHashMap<>(values);
    next.put(name, value);
    return new Quantities(next);
  }

  BigDecimal get(String name) {
    BigDecimal value = values.get(name);
    if (value == null) {
      throw new IllegalArgumentException("quantity not available: " + name);
    }
    return value;
  }

  Set<String> names() {
    return values.keySet();
  }
}

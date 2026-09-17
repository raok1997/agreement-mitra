package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Rent quantities derived from the month-by-month schedule, never approximated as monthly rent
 * times twelve: escalation compounds at each interval and rent-free months pay nothing.
 */
final class RentSchedule {

  private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private RentSchedule() {}

  /** Total rent over the term. Exact (multiplication and addition only). */
  static BigDecimal totalRent(DutyBasis basis) {
    BigDecimal rent = basis.monthlyRent();
    BigDecimal factor = BigDecimal.ONE.add(basis.escalationPercent().divide(HUNDRED));
    BigDecimal total = BigDecimal.ZERO;
    for (int month = 1; month <= basis.termMonths(); month++) {
      if (month > basis.rentFreeMonths()) {
        total = total.add(rent);
      }
      if (basis.escalationEveryMonths() > 0 && month % basis.escalationEveryMonths() == 0) {
        rent = rent.multiply(factor);
      }
    }
    return total;
  }

  /** Total rent times twelve over the term in months. */
  static BigDecimal averageAnnualRent(DutyBasis basis) {
    return totalRent(basis)
        .multiply(TWELVE)
        .divide(BigDecimal.valueOf(basis.termMonths()), MathContext.DECIMAL128);
  }
}

package in.agreementmitra.signing.agreement;

import java.time.LocalDate;
import java.time.Period;

/**
 * Derives the tenancy term, in whole months, from the start and end dates. The dates are the source
 * of truth; the month count is a derived convenience surfaced as the duration shown to the user
 * (product decision: display unit is months, not a years/months/days breakdown).
 *
 * <p>Whole months are measured exclusive of the end date, per {@link Period#between} semantics
 * (e.g. 1 Jan to 1 Dec is 11 months); a trailing partial month is truncated.
 */
final class TenancyDuration {

  private TenancyDuration() {}

  static int months(LocalDate start, LocalDate end) {
    return (int) Period.between(start, end).toTotalMonths();
  }
}

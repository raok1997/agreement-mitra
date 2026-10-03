package in.agreementmitra.signing.agreement;

import java.time.LocalDate;
import java.time.Period;

/**
 * Derives the tenancy term, in whole months, from the start and end dates. The dates are the source
 * of truth; the month count is a derived convenience surfaced as the duration shown to the user
 * (product decision: display unit is months, not a years/months/days breakdown).
 *
 * <p>Whole months are measured INCLUSIVE of the end date -- the end date is the tenancy's last day
 * -- so 1 Sep to 31 Jul is 11 months (and 1 Jan to 1 Dec is still 11); a trailing partial month is
 * truncated. Mirrors {@code documents}' {@code TermMonths}; both pin the same boundary table.
 */
final class TenancyDuration {

  private TenancyDuration() {}

  static int months(LocalDate start, LocalDate end) {
    return (int) Period.between(start, end.plusDays(1)).toTotalMonths();
  }
}

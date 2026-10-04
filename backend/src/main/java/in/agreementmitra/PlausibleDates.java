package in.agreementmitra;

import java.time.LocalDate;

/**
 * The calendar range a date entered into this product may fall in. One definition, used at every
 * input boundary that accepts a date -- {@code signing}'s create/edit request and {@code
 * documents}' submitted-data validator -- so the two cannot drift apart.
 *
 * <p>Why a bound exists at all: ISO-8601 parsing accepts a signed, more-than-four-digit year, so
 * {@code "+999999999-12-31"} is a valid {@link LocalDate}. Downstream arithmetic does not survive
 * it: the end-inclusive month count adds a day to the end date and overflows, and {@code signing}'s
 * term is an {@code int}, which a year just below the maximum silently wraps to an arbitrary,
 * possibly negative, value. Rejecting such dates where they enter means no helper has to defend
 * itself against them.
 *
 * <p>The range is deliberately generous: {@value #MIN_YEAR} admits backdated paperwork for any
 * tenancy that could still be live, and {@value #MAX_YEAR} admits a 99-year lease starting this
 * century. It exists to stop nonsense, not to encode a business rule.
 */
public final class PlausibleDates {

  public static final int MIN_YEAR = 1900;
  public static final int MAX_YEAR = 2199;

  private PlausibleDates() {}

  /** True when {@code date} is null-free and its year lies within the accepted range. */
  public static boolean isPlausible(LocalDate date) {
    return date != null && !isTooEarly(date) && !isTooLate(date);
  }

  /** True when {@code date}'s year is before {@value #MIN_YEAR}. */
  public static boolean isTooEarly(LocalDate date) {
    return date.getYear() < MIN_YEAR;
  }

  /** True when {@code date}'s year is after {@value #MAX_YEAR}. */
  public static boolean isTooLate(LocalDate date) {
    return date.getYear() > MAX_YEAR;
  }
}

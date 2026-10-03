package in.agreementmitra.documents.template;

import java.time.LocalDate;
import java.time.Period;

/**
 * Derives a term in whole months from a start and an end date: the number of COMPLETE months
 * between them, counting the end date as the tenancy's last day (inclusive), with a trailing
 * partial month truncated (1 Sep to 31 Jul is 11 months; 1 Jan to 1 Dec and 1 Jan to 20 Dec are
 * also 11). Inclusive because a deed's end date is the last day of occupation -- measuring
 * exclusive of it drops a month from every term that ends the day before an anniversary, and the
 * term feeds stamp duty and the registration threshold.
 *
 * <p>This deliberately mirrors {@code signing}'s {@code TenancyDuration}, which computes the same
 * count for the persisted aggregate. The duplication is not an oversight: {@code TenancyDuration}
 * is package-private inside another Spring Modulith module, and {@code documents} must not reach
 * into it (nor should a calendar helper be promoted to a module's public API just to be shared).
 * Both delegate to {@link Period#between}, so they agree by construction rather than by
 * coincidence, and both are pinned by the same boundary-case table in their unit tests.
 */
final class TermMonths {

  private TermMonths() {}

  /**
   * The whole-month term between two ISO date strings, or {@code null} when either is absent,
   * blank, or not an ISO date. A {@code null} return means "not determined" -- the caller leaves
   * the field unset rather than substituting a guess, so the document renders it as any other
   * unfilled field.
   */
  static Long between(Object startIso, Object endIso) {
    LocalDate start = parse(startIso);
    LocalDate end = parse(endIso);
    if (start == null || end == null) {
      return null;
    }
    return Period.between(start, end.plusDays(1)).toTotalMonths();
  }

  /** Parse an ISO date, or {@code null} for anything that is not one. Never throws. */
  private static LocalDate parse(Object raw) {
    if (!(raw instanceof String s) || s.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(s.trim());
    } catch (RuntimeException e) {
      // Not an ISO date. The submitted-data validator reports the real error against the date
      // field itself; the term simply stays undetermined. Nothing is logged -- a submitted value
      // must never reach the logs.
      return null;
    }
  }
}

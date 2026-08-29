package in.agreementmitra.signing.payment;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * An amount as <b>integer minor units</b> (paise) plus an explicit currency (design D6).
 *
 * <p>There is no floating-point type anywhere in the monetary path. The provider requires paise and
 * rejects strings and floats; more importantly, floating-point money produces off-by-one-paise
 * errors that fail the amount-equality check a confirmation depends on and are miserable to
 * reconcile afterwards. Deciding this once, at the boundary, avoids it everywhere.
 *
 * <p>{@link #toMajorUnits()} is the single conversion point to the vendor-neutral confirmation
 * seam, which records a {@link BigDecimal}. It is exact - a scaled {@code BigDecimal}, never a
 * division.
 *
 * @param minorUnits paise; always positive
 * @param currency ISO-4217 code, upper case
 */
record Money(long minorUnits, String currency) {

  /** Minor units per major unit for the currencies transacted here (INR: 100 paise). */
  private static final int MINOR_UNIT_SCALE = 2;

  Money {
    if (minorUnits <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency is required");
    }
    currency = currency.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * The same amount as an exact {@link BigDecimal} in major units, for the vendor-neutral
   * confirmation seam. {@code BigDecimal.valueOf(4999, 2)} is {@code 49.99} exactly - a scale
   * change, not a division, so nothing is rounded and nothing is lost.
   */
  BigDecimal toMajorUnits() {
    return BigDecimal.valueOf(minorUnits, MINOR_UNIT_SCALE);
  }

  /** Whether a reported amount and currency match this one exactly (design D8). */
  boolean matches(long reportedMinorUnits, String reportedCurrency) {
    return minorUnits == reportedMinorUnits
        && reportedCurrency != null
        && currency.equalsIgnoreCase(reportedCurrency.trim());
  }
}

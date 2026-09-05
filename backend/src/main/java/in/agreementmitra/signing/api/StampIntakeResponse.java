package in.agreementmitra.signing.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a staff member sees after a successful e-stamp upload: enough context to confirm, at a
 * glance, that the certificate they just spent landed on the right instrument.
 *
 * <p>That confirmation is the point. The real risk in a manual loop is not a rejected upload - it
 * is an accepted one against the wrong agreement, because a purchased certificate is then gone. The
 * check character on the reference prevents a typo resolving elsewhere; this echo lets a human
 * notice if the reference itself was the wrong one.
 *
 * <p>Deliberately <b>non-PII</b>: the agreement's tracking reference, the property <em>city</em>
 * (never the full address), the agreement date, and the certificate number <b>redacted</b> to its
 * last four characters. No party names, no contact details, no storage keys.
 */
public record StampIntakeResponse(
    UUID agreementId,
    String trackingReference,
    String propertyCity,
    LocalDate agreementStartDate,
    String certificateNumberRedacted,
    /*
     * Whether the signing workflow was started as part of this upload. Reported SEPARATELY from the
     * stamp outcome on purpose: the two can disagree, and a purchased certificate is never rolled
     * back to make a single result look tidy. False also when signing was not asked for.
     */
    boolean signingInitiated,
    /*
     * Why signing did not start, when it was asked for and failed. A short, fixed reason token --
     * never a vendor payload, never a party detail, never an exception message.
     */
    String signingNotStartedReason) {

  /** The ordinary outcome: a stamp attached, with no signing asked for. */
  public static StampIntakeResponse stamped(
      UUID agreementId,
      String trackingReference,
      String propertyCity,
      LocalDate agreementStartDate,
      String certificateNumberRedacted) {
    return new StampIntakeResponse(
        agreementId,
        trackingReference,
        propertyCity,
        agreementStartDate,
        certificateNumberRedacted,
        false,
        null);
  }

  /** The same stamp outcome, plus what became of the signing step that was asked for. */
  public StampIntakeResponse withSigning(boolean initiated, String notStartedReason) {
    return new StampIntakeResponse(
        agreementId,
        trackingReference,
        propertyCity,
        agreementStartDate,
        certificateNumberRedacted,
        initiated,
        notStartedReason);
  }
}

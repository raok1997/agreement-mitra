package in.agreementmitra.signing.signingrequest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One staff stamp-upload attempt, as the service sees it: which agreement (by its staff reference),
 * the scan bytes, and the certificate metadata the staff member transcribed.
 *
 * <p>Field-shape validation (mandatory fields, sizes, formats) has already run at the {@code api}
 * layer, so a command that reaches the service is well-formed; the service still owns every
 * <em>semantic</em> gate (agreement exists, not already stamped, scan is a real image, certificate
 * not already spent).
 *
 * <p><b>PII:</b> {@code purchasedBy} is a party name and {@code documentDescription} describes the
 * property, so {@link #toString()} is redacted - the default record rendering would dump the scan's
 * identity hash plus every transcribed value into any log line that printed it.
 */
public record StampIntakeCommand(
    String agreementReference,
    byte[] scan,
    String certificateNumber,
    LocalDate issueDate,
    BigDecimal dutyAmount,
    String jurisdiction,
    String documentDescription,
    String purchasedBy,
    /* Start the signing workflow once the stamp is attached. Opt-in; see StampIntakeRequest. */
    boolean initiateSigning) {

  @Override
  public String toString() {
    // No submitted metadata value, and no scan bytes. Nothing here identifies a party.
    return "StampIntakeCommand{scanBytes=" + (scan == null ? 0 : scan.length) + "}";
  }
}

package in.agreementmitra.signing.stamp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The SHCIL e-stamp certificate metadata a staff member transcribes from the certificate they
 * purchased out-of-band, alongside its scan. Vendor-neutral input to the {@link StampProvider}
 * seam.
 *
 * <p>{@code certificateNumber}, {@code issueDate}, {@code dutyAmount} and {@code jurisdiction} are
 * mandatory; {@code documentDescription} and {@code purchasedBy} are optional. Nothing here is
 * machine-verified against SHCIL - for v1 staff <b>attest</b> to what they uploaded, and automated
 * certificate verification is recorded as the follow-up hardening.
 *
 * <p><b>PII:</b> {@code purchasedBy} is a party name and {@code documentDescription} describes the
 * property, so {@link #toString()} is overridden to redact - the default record rendering would put
 * both, plus the whole certificate number, into any log line that printed this value.
 */
public record StampCertificate(
    String certificateNumber,
    LocalDate issueDate,
    BigDecimal dutyAmount,
    String jurisdiction,
    String documentDescription,
    String purchasedBy) {

  @Override
  public String toString() {
    return "StampCertificate{certificateNumber="
        + CertificateNumbers.redact(certificateNumber)
        + ", jurisdiction="
        + jurisdiction
        + "}";
  }
}

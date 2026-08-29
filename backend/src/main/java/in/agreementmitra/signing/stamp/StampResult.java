package in.agreementmitra.signing.stamp;

import java.math.BigDecimal;

/**
 * The outcome of attaching an e-stamp: the SHCIL certificate number, its jurisdiction and duty
 * amount, whether real duty was paid, and the composited stamped PDF bytes. Vendor-neutral.
 *
 * <p>{@code dutyPaid} is {@code true} for every attachment, because the certificate represents duty
 * that a staff member actually paid on the SHCIL portal before uploading it. The value is
 * authoritative and is copied verbatim onto the agreement's stored stamp data. (In this repository
 * the fixtures are synthetic images with fabricated certificate numbers - the flag records what the
 * flow means, not that a test paid anything.)
 *
 * <p><b>PII:</b> {@link #toString()} is overridden to redact the certificate number; a record's
 * default rendering would print it in full. The PDF bytes render as an identity hash either way.
 */
public record StampResult(
    String certificateNumber,
    String jurisdiction,
    BigDecimal dutyAmount,
    boolean dutyPaid,
    byte[] stampedPdf) {

  @Override
  public String toString() {
    return "StampResult{certificateNumber="
        + CertificateNumbers.redact(certificateNumber)
        + ", jurisdiction="
        + jurisdiction
        + ", dutyPaid="
        + dutyPaid
        + "}";
  }
}

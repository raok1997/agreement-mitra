package in.agreementmitra.signing.stamp;

import in.agreementmitra.StampFailedException;

/**
 * Vendor-neutral e-stamp seam, parallel to {@code EsignProvider}. Attaching a stamp means taking a
 * certificate that already exists - purchased out-of-band by staff on the SHCIL portal - and
 * compositing it onto the agreement's draft to produce the legally-stampable instrument.
 *
 * <p>The system NEVER generates, synthesises, or procures a stamp, and holds no SHCIL credential or
 * endpoint. The seam survives (design D1) not because it once hid a generator, but because it keeps
 * stamp specifics out of the signing flow: a vendor auto-affix product (Leegality lists one) or a
 * future real procurement API would slot in behind this interface with no caller change.
 */
public interface StampProvider {

  /**
   * Attach {@code certificate} - evidenced by {@code certificateScan} - to {@code draftPdf}.
   *
   * @param draftPdf the untrusted, user-uploaded draft PDF bytes
   * @param certificateScan the untrusted, staff-uploaded certificate scan (JPEG/PNG, already
   *     validated by {@link CertificateScanValidator})
   * @param certificate the certificate metadata staff transcribed from the purchased e-stamp
   * @return the certificate number, jurisdiction, duty amount, {@code dutyPaid = true}, and the
   *     composited stamped PDF bytes
   * @throws StampFailedException if the draft or the scan cannot be parsed, decoded, or composited
   *     (fail closed)
   */
  StampResult attach(byte[] draftPdf, byte[] certificateScan, StampCertificate certificate);
}

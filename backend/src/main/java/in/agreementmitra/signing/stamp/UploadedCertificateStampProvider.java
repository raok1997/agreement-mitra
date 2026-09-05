package in.agreementmitra.signing.stamp;

import org.springframework.stereotype.Component;

/**
 * The {@link StampProvider} for the manual SHCIL flow: staff buy a real e-stamp certificate on the
 * SHCIL portal, print it, scan it, and upload it. This adapter composites what they uploaded - it
 * contacts no external service, holds no credential, and generates nothing. It replaces the removed
 * synthetic Karnataka adapter, whose deterministic BW-series serial and {@code dutyPaid = false}
 * were dummy data that could never make a legally stampable instrument.
 *
 * <p>Because the certificate evidences duty actually paid out-of-band, the result carries {@code
 * dutyPaid = true}, and the certificate number - supplied, never derived - is the authoritative
 * identifier that both the stored stamp data and the per-page overlay carry.
 */
@Component
class UploadedCertificateStampProvider implements StampProvider {

  private final PdfStampComposer composer;

  UploadedCertificateStampProvider(PdfStampComposer composer) {
    this.composer = composer;
  }

  @Override
  public StampResult attach(byte[] draftPdf, byte[] certificateScan, StampCertificate certificate) {
    byte[] stampedPdf = composer.compose(draftPdf, certificateScan);
    return new StampResult(
        certificate.certificateNumber(),
        certificate.jurisdiction(),
        certificate.dutyAmount(),
        true,
        stampedPdf);
  }
}

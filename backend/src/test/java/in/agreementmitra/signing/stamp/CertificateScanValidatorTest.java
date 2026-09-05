package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CertificateScanValidator}: only real, bounded JPEG/PNG rasters pass. */
class CertificateScanValidatorTest {

  private final CertificateScanValidator validator = new CertificateScanValidator();

  @Test
  void acceptsPngAndReportsItsTrueContentType() {
    assertThat(validator.validate(TestImages.png(1000, 1400)))
        .isEqualTo(CertificateScanValidator.CONTENT_TYPE_PNG);
  }

  @Test
  void acceptsJpegAndReportsItsTrueContentType() {
    assertThat(validator.validate(TestImages.jpeg(1000, 1400)))
        .isEqualTo(CertificateScanValidator.CONTENT_TYPE_JPEG);
  }

  @Test
  void rejectsAPdfEvenThoughItIsAValidDocument() {
    // The stamp intake accepts a SCAN, not a document. A PDF is refused on magic bytes.
    assertThatThrownBy(() -> validator.validate(TestPdfs.singlePage()))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsMagicByteMismatchRegardlessOfWhatTheCallerClaims() {
    // A PNG-extension file whose bytes are a JPEG-less blob: the leading bytes decide, nothing
    // else.
    byte[] disguised = "PNG? no. this is arbitrary content".getBytes();
    assertThatThrownBy(() -> validator.validate(disguised))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsAnEmptyUpload() {
    assertThatThrownBy(() -> validator.validate(new byte[0]))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsBytesOverTheCeiling() {
    byte[] oversize = new byte[CertificateScanValidator.MAX_BYTES + 1];
    // Give it valid PNG magic bytes so it is the SIZE check that refuses it, not the type check.
    System.arraycopy(TestImages.png(300, 300), 0, oversize, 0, 8);
    assertThatThrownBy(() -> validator.validate(oversize))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsADecompressionBombWithoutDecodingIt() {
    // A few dozen bytes declaring a 30000x30000 raster: bounded by the byte ceiling, lethal if
    // decoded. The pixel ceiling is the only check that catches it.
    byte[] bomb = TestImages.bombPng(30_000, 30_000);
    assertThat(bomb.length).isLessThan(1024);
    assertThatThrownBy(() -> validator.validate(bomb)).isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsAScanBelowTheMinimumUsableSize() {
    assertThatThrownBy(() -> validator.validate(TestImages.png(40, 40)))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void rejectsATruncatedImageAsAClientError() {
    // Fails closed as a mapped 400 - never an unmapped server error.
    assertThatThrownBy(() -> validator.validate(truncatedHeader()))
        .isInstanceOf(InvalidUploadException.class);
  }

  /** PNG magic bytes with nothing behind them: the header itself cannot be read. */
  private static byte[] truncatedHeader() {
    byte[] full = TestImages.png(1000, 1400);
    byte[] truncated = new byte[12];
    System.arraycopy(full, 0, truncated, 0, truncated.length);
    return truncated;
  }
}

package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.StampFailedException;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the uploaded-certificate adapter: it composites what staff uploaded, reports the
 * supplied certificate number verbatim (never a derived serial), carries {@code dutyPaid = true}
 * because a real certificate was bought out-of-band, and leaks nothing through {@code toString}.
 */
class UploadedCertificateStampProviderTest {

  private final StampProvider provider =
      new UploadedCertificateStampProvider(new PdfStampComposer());

  private static StampCertificate certificate() {
    return new StampCertificate(
        "IN-KA12345678901234X",
        LocalDate.of(2026, 1, 15),
        new BigDecimal("500.00"),
        "KA",
        "Rental agreement",
        "AgreementMitra Operations");
  }

  @Test
  void attachesTheUploadedCertificateWithDutyPaid() throws Exception {
    StampResult result =
        provider.attach(TestPdfs.singlePage(), TestImages.certificateScan(), certificate());

    assertThat(result.dutyPaid()).isTrue(); // a real certificate was purchased out-of-band
    assertThat(result.certificateNumber()).isEqualTo("IN-KA12345678901234X");
    assertThat(result.jurisdiction()).isEqualTo("KA");
    assertThat(result.dutyAmount()).isEqualByComparingTo("500.00");
    // A real composited PDF: the scan page plus the one draft page.
    try (PDDocument doc = Loader.loadPDF(result.stampedPdf())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
    }
  }

  @Test
  void anUndecodableScanFailsClosed() {
    byte[] broken = TestImages.truncatedPng(1000, 1400);
    assertThatThrownBy(() -> provider.attach(TestPdfs.singlePage(), broken, certificate()))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void toStringLeaksNeitherPdfBytesNorTheFullCertificateNumber() {
    StampResult result =
        provider.attach(TestPdfs.singlePage(), TestImages.certificateScan(), certificate());

    assertThat(result.toString())
        .doesNotContain("%PDF")
        .doesNotContain("IN-KA12345678901234X")
        .contains("***");
  }
}

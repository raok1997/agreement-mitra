package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.TestPdfs;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the v1 synthetic Karnataka adapter: it pays no real duty, returns an authoritative
 * {@code dutyPaid=false} + a synthetic BW-series serial + denomination/jurisdiction, composites a
 * real stamped PDF, and leaks no PDF bytes through {@code toString} (PII hygiene).
 */
class SyntheticKarnatakaStampProviderTest {

  private final StampProvider provider =
      new SyntheticKarnatakaStampProvider(new PdfStampComposer());

  @Test
  void procuresASyntheticKarnatakaStampWithNoRealDuty() throws Exception {
    UUID agreementId = UUID.randomUUID();

    StampResult result = provider.procure(agreementId, TestPdfs.singlePage());

    assertThat(result.dutyPaid()).isFalse(); // synthetic — no real duty
    assertThat(result.jurisdiction()).isEqualTo("KA");
    assertThat(result.denomination()).isEqualTo(100);
    assertThat(result.serial()).matches("BW \\d{10}");
    // The serial is deterministic from the agreement id.
    assertThat(result.serial()).isEqualTo(StampSerials.forAgreement(agreementId));
    // A real composited PDF: stamp page + the one draft page.
    try (PDDocument doc = Loader.loadPDF(result.stampedPdf())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
    }
  }

  @Test
  void toStringDoesNotLeakPdfBytes() {
    StampResult result = provider.procure(UUID.randomUUID(), TestPdfs.singlePage());
    // A record's default toString renders the byte[] as an identity hash, never its content.
    assertThat(result.toString()).doesNotContain("%PDF");
  }
}

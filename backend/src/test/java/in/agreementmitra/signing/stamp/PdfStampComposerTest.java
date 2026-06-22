package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.StampFailedException;
import in.agreementmitra.support.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PdfStampComposer} — page-prepend, serial overlay, and fail-closed parsing.
 */
class PdfStampComposerTest {

  private final PdfStampComposer composer = new PdfStampComposer();

  @Test
  void stampedPdfHasOneMorePageThanTheDraft() throws Exception {
    byte[] stamped = composer.compose(TestPdfs.pages(3), "BW 0000012345");
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(4); // 1 stamp page + 3 draft pages
    }
  }

  @Test
  void overlayCarriesTheSerialOnTheDocument() throws Exception {
    byte[] stamped = composer.compose(TestPdfs.singlePage(), "BW 0000012345");
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      String text = new PDFTextStripper().getText(doc);
      assertThat(text).contains("Non Judicial Stamp No. BW 0000012345");
    }
  }

  @Test
  void originalDraftBytesAreNotMutated() {
    byte[] draft = TestPdfs.singlePage();
    byte[] copy = Arrays.copyOf(draft, draft.length);
    composer.compose(draft, "BW 0000000001");
    assertThat(draft).isEqualTo(copy);
  }

  @Test
  void overlayStaysInFrameForLandscapePages() throws Exception {
    // A landscape draft (wide media box) — overlay positioned by media box must still extract.
    byte[] landscape = landscapeSinglePage();
    byte[] stamped = composer.compose(landscape, "BW 0000099999");
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(new PDFTextStripper().getText(doc)).contains("BW 0000099999");
    }
  }

  @Test
  void corruptDraftFailsClosed() {
    byte[] corrupt = "%PDF-1.4 not a real pdf".getBytes();
    assertThatThrownBy(() -> composer.compose(corrupt, "BW 0000000001"))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void zeroPageDraftFailsClosed() {
    assertThatThrownBy(() -> composer.compose(TestPdfs.pages(0), "BW 0000000001"))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void encryptedDraftFailsClosed() throws Exception {
    assertThatThrownBy(() -> composer.compose(encryptedSinglePage(), "BW 0000000001"))
        .isInstanceOf(StampFailedException.class);
  }

  private static byte[] landscapeSinglePage() throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.addPage(
          new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())));
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static byte[] encryptedSinglePage() throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      doc.save(out);
      return out.toByteArray();
    }
  }
}

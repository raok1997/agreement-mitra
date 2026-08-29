package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import in.agreementmitra.StampFailedException;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PdfStampComposer}: the uploaded scan becomes page 1, the certificate number
 * rides every document page, the fit geometry preserves aspect and never upscales, and both
 * untrusted inputs fail closed.
 */
class PdfStampComposerTest {

  private static final String CERTIFICATE = "IN-KA12345678901234X";

  private final PdfStampComposer composer = new PdfStampComposer();

  @Test
  void stampedPdfHasOneMorePageThanTheDraft() throws Exception {
    byte[] stamped = composer.compose(TestPdfs.pages(3), TestImages.certificateScan());
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(4); // 1 certificate page + 3 draft pages
    }
  }

  @Test
  void scannedCertificateIsPageOne() throws Exception {
    byte[] stamped = composer.compose(TestPdfs.singlePage(), TestImages.certificateScan());
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(imageCountOn(doc, 0)).isEqualTo(1); // the scan
      assertThat(imageCountOn(doc, 1)).isZero(); // the draft page carries no raster
    }
  }

  @Test
  void noCertificateNumberIsStampedOntoTheDocumentPages() throws Exception {
    // This once printed "e-Stamp Certificate No. <n>" across the top of every agreement page, and
    // the number it printed was whatever intake transcribed - in practice the agreement's own
    // tracking reference. A deed must not assert stamp evidence it cannot vouch for, so nothing is
    // stamped at all now: the number is still stored and still single-use, just not printed.
    byte[] stamped = composer.compose(TestPdfs.singlePage(), TestImages.certificateScan());

    try (PDDocument doc = Loader.loadPDF(stamped)) {
      String text = new PDFTextStripper().getText(doc);
      assertThat(text).doesNotContain("e-Stamp Certificate No.");
      assertThat(text).doesNotContain(CERTIFICATE);
    }
  }

  @Test
  void originalDraftAndScanBytesAreNotMutated() {
    byte[] draft = TestPdfs.singlePage();
    byte[] scan = TestImages.certificateScan();
    byte[] draftCopy = Arrays.copyOf(draft, draft.length);
    byte[] scanCopy = Arrays.copyOf(scan, scan.length);

    composer.compose(draft, scan);

    assertThat(draft).isEqualTo(draftCopy);
    assertThat(scan).isEqualTo(scanCopy);
  }

  @Test
  void fitPreservesAspectRatioWhenScalingDown() {
    // 1000x2000 (aspect 0.5) into a 500x500 box -> height-bound, aspect unchanged.
    float[] drawn = PdfStampComposer.fitWithoutUpscaling(1000f, 2000f, 500f, 500f);
    assertThat(drawn[0]).isCloseTo(250f, within(0.01f));
    assertThat(drawn[1]).isCloseTo(500f, within(0.01f));
    assertThat(drawn[0] / drawn[1]).isCloseTo(0.5f, within(0.001f));
  }

  @Test
  void fitNeverUpscalesBeyondNativeResolution() {
    // A small scan inside a big box stays at its native size rather than being blown up.
    float[] drawn = PdfStampComposer.fitWithoutUpscaling(120f, 90f, 500f, 700f);
    assertThat(drawn[0]).isEqualTo(120f);
    assertThat(drawn[1]).isEqualTo(90f);
  }

  @Test
  void landscapeDraftPagesComposeWithTheirOrientationIntact() throws Exception {
    // Used to assert the stamped overlay landed inside a wide media box. Nothing is stamped now,
    // but the case still has to compose - and a landscape page must not come back portrait.
    byte[] draft = landscapeSinglePage();
    float expectedWidth;
    try (PDDocument original = Loader.loadPDF(draft)) {
      expectedWidth = original.getPage(0).getMediaBox().getWidth();
    }

    byte[] stamped = composer.compose(draft, TestImages.certificateScan());

    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2); // certificate + the landscape page
      PDRectangle box = doc.getPage(1).getMediaBox();
      assertThat(box.getWidth()).isEqualTo(expectedWidth);
      assertThat(box.getWidth()).isGreaterThan(box.getHeight());
    }
  }

  @Test
  void rotatedDraftPagesComposeWithTheirRotationIntact() throws Exception {
    // Same reasoning as the landscape case: the overlay is gone, the rotated page is not. A
    // rotation silently dropped here would re-orient a signed instrument.
    byte[] stamped = composer.compose(rotatedSinglePage(270), TestImages.certificateScan());

    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
      assertThat(doc.getPage(1).getRotation()).isEqualTo(270);
    }
  }

  @Test
  void corruptDraftFailsClosed() {
    byte[] corrupt = "%PDF-1.4 not a real pdf".getBytes();
    assertThatThrownBy(() -> composer.compose(corrupt, TestImages.certificateScan()))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void zeroPageDraftFailsClosed() {
    assertThatThrownBy(() -> composer.compose(TestPdfs.pages(0), TestImages.certificateScan()))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void encryptedDraftFailsClosed() throws Exception {
    byte[] encrypted = encryptedSinglePage();
    assertThatThrownBy(() -> composer.compose(encrypted, TestImages.certificateScan()))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void truncatedScanFailsClosed() {
    byte[] truncated = TestImages.truncatedPng(1000, 1400);
    assertThatThrownBy(() -> composer.compose(TestPdfs.singlePage(), truncated))
        .isInstanceOf(StampFailedException.class);
  }

  @Test
  void nonImageScanFailsClosed() {
    byte[] notAnImage = "this is definitely not an image".getBytes();
    assertThatThrownBy(() -> composer.compose(TestPdfs.singlePage(), notAnImage))
        .isInstanceOf(StampFailedException.class);
  }

  private static int imageCountOn(PDDocument doc, int pageIndex) throws Exception {
    PDPage page = doc.getPage(pageIndex);
    if (page.getResources() == null) {
      return 0; // no resource dictionary at all - nothing is drawn on the page
    }
    int images = 0;
    for (var name : page.getResources().getXObjectNames()) {
      PDXObject xObject = page.getResources().getXObject(name);
      if (xObject instanceof PDImageXObject) {
        images++;
      }
    }
    return images;
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

  private static byte[] rotatedSinglePage(int rotation) throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      page.setRotation(rotation);
      doc.addPage(page);
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

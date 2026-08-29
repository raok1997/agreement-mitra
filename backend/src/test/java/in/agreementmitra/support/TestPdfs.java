package in.agreementmitra.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Generates real, parseable PDF bytes for tests. Since CR-6 the signing flow parses the uploaded
 * draft with PDFBox to stamp it, so a draft must be a genuine PDF (a {@code "%PDF-..."} string with
 * valid magic bytes but no structure no longer suffices).
 */
public final class TestPdfs {

  private TestPdfs() {}

  /** A valid one-page A4 PDF. */
  public static byte[] singlePage() {
    return pages(1);
  }

  /**
   * A valid one-page A4 PDF carrying the two {@code esign:<role>} anchors the renderer emits at
   * each signature zone, as real extractable text. Needed by anything that maps an anchor to a
   * provider coordinate - a blank page has no text layer to find.
   *
   * <p>The owner anchor is drawn near the LEFT edge and the tenant anchor near the RIGHT edge, on
   * purpose: a provider that measures x from the right must report them the other way round, so a
   * mirroring mistake is visible in an assertion rather than only in a rendered document.
   */
  public static byte[] withEsignAnchors() {
    return anchored(PDRectangle.A4, 0);
  }

  /**
   * The same anchored page at an arbitrary size and rotation, for checking that the coordinate
   * translation is not quietly assuming A4-portrait.
   *
   * @param size page size
   * @param rotation page rotation in degrees (0/90/180/270)
   */
  public static byte[] anchored(PDRectangle size, int rotation) {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(size);
      page.setRotation(rotation);
      doc.addPage(page);
      float width = size.getWidth();
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        write(content, "esign:owner", 60f, 120f);
        write(content, "esign:tenant", width - 160f, 120f);
      }
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(PDPageContentStream content, String text, float x, float y)
      throws IOException {
    content.beginText();
    content.setFont(new PDType1Font(FontName.HELVETICA), 9f);
    content.newLineAtOffset(x, y);
    content.showText(text);
    content.endText();
  }

  /** A valid {@code n}-page A4 PDF. */
  public static byte[] pages(int n) {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < n; i++) {
        doc.addPage(new PDPage(PDRectangle.A4));
      }
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

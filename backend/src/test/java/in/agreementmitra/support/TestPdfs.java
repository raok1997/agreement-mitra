package in.agreementmitra.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

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

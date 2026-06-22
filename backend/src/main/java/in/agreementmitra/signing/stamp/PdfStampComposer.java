package in.agreementmitra.signing.stamp;

import in.agreementmitra.StampFailedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

/**
 * Composites a synthetic e-stamp onto a draft PDF with Apache PDFBox: prepends a hardcoded ₹100
 * (rendered "INR 100" — Standard-14 fonts have no ₹ glyph) Karnataka non-judicial stamp page as
 * page 1, then overlays a per-page header carrying the serial onto each draft page. The result has
 * {@code 1 + draftPages} pages; the input draft bytes are never mutated.
 *
 * <p>The draft is <b>untrusted</b> (only its {@code %PDF-} magic bytes were checked at upload), so
 * parsing fails <b>closed</b>: encrypted, corrupt, truncated, or zero-page drafts all raise {@link
 * StampFailedException} (never an unmapped error, hang, or OOM). Input size is already bounded by
 * the upload ceiling, so the in-memory parse cannot exhaust the heap on a large-but-legal file.
 *
 * <p>Overlay text uses a Standard-14 font (ASCII serial → no bundled font) and is positioned
 * relative to each page's media box, respecting page rotation, so it stays in-frame for any size or
 * orientation.
 */
@Component
class PdfStampComposer {

  private static final float MARGIN = 24f;
  private static final float HEADER_FONT_SIZE = 8f;

  /** Compose the stamped PDF. Returns new bytes; the input array is not modified. */
  byte[] compose(byte[] draftPdf, String serial) {
    try (PDDocument draft = Loader.loadPDF(draftPdf)) {
      if (draft.isEncrypted()) {
        throw new StampFailedException("draft is encrypted");
      }
      if (draft.getNumberOfPages() == 0) {
        throw new StampFailedException("draft has zero pages");
      }
      try (PDDocument result = new PDDocument();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        addStampPage(result, serial);
        for (PDPage page : draft.getPages()) {
          // importPage shares objects with `draft` by reference — save while `draft` is still open.
          overlaySerial(result, result.importPage(page), serial);
        }
        result.save(out);
        return out.toByteArray();
      }
    } catch (InvalidPasswordException e) {
      throw new StampFailedException("draft is password-protected", e);
    } catch (IOException e) {
      throw new StampFailedException("draft could not be parsed or composited", e);
    }
  }

  private void addStampPage(PDDocument doc, String serial) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    PDType1Font bold = new PDType1Font(FontName.HELVETICA_BOLD);
    PDType1Font normal = new PDType1Font(FontName.HELVETICA);
    float top = PDRectangle.A4.getHeight() - 90f;
    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
      line(cs, bold, 18f, top, "GOVERNMENT OF KARNATAKA");
      line(cs, bold, 14f, top - 32f, "NON-JUDICIAL STAMP PAPER");
      line(cs, normal, 12f, top - 72f, "Denomination: INR 100");
      line(cs, normal, 12f, top - 94f, "Stamp Serial No.: " + serial);
      line(cs, normal, 10f, top - 140f, "(SYNTHETIC e-STAMP - SANDBOX - NO DUTY PAID)");
    }
  }

  private void line(PDPageContentStream cs, PDType1Font font, float size, float y, String text)
      throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(70f, y);
    cs.showText(text);
    cs.endText();
  }

  private void overlaySerial(PDDocument doc, PDPage page, String serial) throws IOException {
    PDRectangle box = page.getMediaBox();
    int rotation = ((page.getRotation() % 360) + 360) % 360;
    PDType1Font font = new PDType1Font(FontName.HELVETICA);
    try (PDPageContentStream cs =
        new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
      cs.beginText();
      cs.setFont(font, HEADER_FONT_SIZE);
      cs.setNonStrokingColor(0.4f, 0.4f, 0.4f);
      cs.setTextMatrix(headerMatrix(box, rotation));
      cs.showText("Non Judicial Stamp No. " + serial);
      cs.endText();
    }
  }

  /** Place the header near the visual top-left of the page, accounting for its rotation. */
  private static Matrix headerMatrix(PDRectangle box, int rotation) {
    return switch (rotation) {
      case 90 -> Matrix.getRotateInstance(Math.toRadians(90), box.getWidth() - MARGIN, MARGIN);
      case 180 -> Matrix.getRotateInstance(Math.toRadians(180), box.getWidth() - MARGIN, MARGIN);
      case 270 -> Matrix.getRotateInstance(Math.toRadians(270), MARGIN, box.getHeight() - MARGIN);
      default -> Matrix.getTranslateInstance(MARGIN, box.getHeight() - MARGIN);
    };
  }
}

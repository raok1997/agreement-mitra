package in.agreementmitra.signing.stamp;

import in.agreementmitra.StampFailedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * Composites the staff-uploaded e-stamp certificate onto a draft PDF with Apache PDFBox: prepends
 * the <b>scanned certificate image</b> as page 1 and leaves the draft's own pages untouched. The
 * result has {@code 1 + draftPages} pages; the input draft bytes and the input scan bytes are never
 * mutated.
 *
 * <p><b>Nothing is drawn onto the agreement pages</b> - in particular no certificate-number header.
 * See {@link #compose} for why that was removed and what still carries the tie between the
 * certificate page and the body.
 *
 * <p>The scan is fitted inside the page's printable area with its <b>aspect ratio preserved</b> and
 * is never <b>upscaled beyond its native resolution</b> (its pixel dimensions read as points at 72
 * dpi) - a stretched or blown-up certificate would read as a forgery-grade artefact on a document
 * that evidences real duty.
 *
 * <p>Both inputs are <b>untrusted</b>: the draft had only its {@code %PDF-} magic bytes checked at
 * upload, and the scan was validated by {@link CertificateScanValidator} at intake. Parsing fails
 * <b>closed</b>: encrypted, corrupt, truncated, or zero-page drafts and undecodable scans all raise
 * {@link StampFailedException} (never an unmapped error, hang, or OOM). Both inputs' byte sizes are
 * already bounded by their upload ceilings, so an in-memory parse cannot exhaust the heap on a
 * large-but-legal file.
 */
@Component
class PdfStampComposer {

  /** Printable inset for the prepended certificate page, in points. */
  private static final float SCAN_PAGE_MARGIN = 28f;

  /**
   * Compose the stamped PDF: {@code certificateScan} becomes page 1, the draft's pages follow, each
   * Returns new bytes; neither input array is modified.
   *
   * <p><b>Nothing is stamped onto the agreement pages.</b> They used to carry an "e-Stamp
   * Certificate No." header, and it stated a number the platform could not vouch for: the value
   * printed was whatever was transcribed at intake, which in practice was the agreement's own
   * tracking reference rather than a number from the certificate. A legal instrument asserting its
   * own stamp evidence has to be right about it, and a header that can be wrong is worse than no
   * header. The certificate number is still stored against the agreement (and still enforced as
   * single-use); it is simply no longer printed as though the document could attest to it.
   *
   * <p>The tie between the certificate page and the body survives without it: every rendered page
   * already carries the tracking reference in its footer, and the certificate is bound into the
   * same file as page 1.
   *
   * @throws StampFailedException if either input cannot be parsed/decoded, or composition fails
   */
  byte[] compose(byte[] draftPdf, byte[] certificateScan) {
    try (PDDocument draft = Loader.loadPDF(draftPdf)) {
      if (draft.isEncrypted()) {
        throw new StampFailedException("draft is encrypted");
      }
      if (draft.getNumberOfPages() == 0) {
        throw new StampFailedException("draft has zero pages");
      }
      try (PDDocument result = new PDDocument();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        addCertificatePage(result, certificateScan);
        for (PDPage page : draft.getPages()) {
          // importPage shares objects with `draft` by reference - save while `draft` is still open.
          result.importPage(page);
        }
        result.save(out);
        return out.toByteArray();
      }
    } catch (InvalidPasswordException e) {
      throw new StampFailedException("draft is password-protected", e);
    } catch (IOException e) {
      throw new StampFailedException("draft or certificate scan could not be composited", e);
    } catch (IllegalArgumentException | IllegalStateException e) {
      // PDFBox reports an unrecognised image type as IllegalArgumentException rather than
      // IOException
      // ("Image type UNKNOWN not supported"). Fold it into the same fail-closed path: an
      // undecodable
      // scan must surface as a stamping failure, never as an unmapped 500.
      throw new StampFailedException("certificate scan could not be decoded", e);
    }
  }

  /**
   * Page 1: the scanned certificate, centred inside the printable area, aspect preserved, never
   * upscaled. An undecodable scan raises {@link IOException} from PDFBox and fails closed above.
   */
  private void addCertificatePage(PDDocument doc, byte[] certificateScan) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    PDImageXObject image = PDImageXObject.createFromByteArray(doc, certificateScan, "estamp-scan");
    float boxWidth = PDRectangle.A4.getWidth() - 2 * SCAN_PAGE_MARGIN;
    float boxHeight = PDRectangle.A4.getHeight() - 2 * SCAN_PAGE_MARGIN;
    float[] drawn = fitWithoutUpscaling(image.getWidth(), image.getHeight(), boxWidth, boxHeight);
    float x = (PDRectangle.A4.getWidth() - drawn[0]) / 2f;
    float y = (PDRectangle.A4.getHeight() - drawn[1]) / 2f;
    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
      cs.drawImage(image, x, y, drawn[0], drawn[1]);
    }
  }

  /**
   * The drawn {@code [width, height]} in points for an image of {@code imageWidth x imageHeight}
   * pixels inside a {@code boxWidth x boxHeight} area: scaled down to fit, aspect ratio preserved,
   * and capped at scale 1.0 so a small scan is never blown up past its native resolution.
   * Package-private so the geometry is unit-testable without cracking open a PDF.
   */
  static float[] fitWithoutUpscaling(
      float imageWidth, float imageHeight, float boxWidth, float boxHeight) {
    if (imageWidth <= 0 || imageHeight <= 0) {
      return new float[] {0f, 0f};
    }
    float scale = Math.min(boxWidth / imageWidth, boxHeight / imageHeight);
    scale = Math.min(scale, 1f);
    return new float[] {imageWidth * scale, imageHeight * scale};
  }
}

package in.agreementmitra.signing.zoop;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Locates each {@code esign:<role>} anchor in the <b>stamped</b> PDF's text layer and reports where
 * it sits, in PDF's own bottom-left convention.
 *
 * <p>The {@code documents} renderer emits the anchor as real, visible text at every signature zone
 * (9px grey), and derives it from the signatory field key; the {@code signing} module derives the
 * same token from the signer's role. Neither module shares a type with the other, and neither knows
 * anything about a provider - which is exactly why this translation lives inside the vendor adapter
 * (design D4).
 *
 * <p><b>Why the stamped PDF, not the draft.</b> Stamp intake prepends the scanned SHCIL certificate
 * as page 1, so every page number shifts. Searching the final document handles that with no offset
 * arithmetic at all: whatever page the anchor is found on IS the page the provider must sign.
 *
 * <p>Matching is whitespace-insensitive and case-insensitive: PDF text extraction can split or pad
 * a run, and an anchor that "almost" matched would otherwise be treated as missing.
 *
 * <p>Package-private: no {@code documents} type crosses the module boundary, and nothing outside
 * this adapter needs it.
 */
final class EsignAnchorLocator {

  private EsignAnchorLocator() {}

  /**
   * Find every requested anchor. Anchors that are not present are simply absent from the result -
   * the caller decides what to do, and for a legal instrument that decision is to refuse rather
   * than place a signature at a guessed position.
   *
   * @param pdf the stamped PDF bytes
   * @param anchors the {@code esign:<role>} tokens to look for
   * @return anchor token to its position, for those found
   * @throws IllegalStateException if the PDF cannot be parsed
   */
  static Map<String, AnchorPosition> locate(byte[] pdf, Collection<String> anchors) {
    List<String> wanted =
        anchors.stream()
            .filter(a -> a != null && !a.isBlank())
            .map(a -> a.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    if (wanted.isEmpty()) {
      return Map.of();
    }
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Stripper stripper = new Stripper(wanted);
      stripper.setSortByPosition(true);
      stripper.setStartPage(1);
      stripper.setEndPage(document.getNumberOfPages());
      stripper.writeText(document, new StringWriter());
      return stripper.found;
    } catch (IOException e) {
      // Never echo document content in the fault - the instrument carries party PII.
      throw new IllegalStateException(
          "Stamped document could not be parsed to locate eSign anchors");
    }
  }

  /**
   * Measure the instrument: how many pages, the smallest of them, and where its body text column
   * begins and ends.
   *
   * <p><b>Smallest page, not first page.</b> A placement applied to several pages has one
   * coordinate and several page sizes - a stamp certificate scanned at a different size than the
   * agreement is the normal case here. Measuring against the smallest width and height keeps the
   * placement on the page for all of them; the cost is a slightly higher strip on the larger pages,
   * which is invisible next to one that falls off the short page.
   *
   * <p><b>The content column is measured, never configured</b> - as the 5th percentile of line
   * starts and the 95th percentile of line ends.
   *
   * <p>It used to be the MEDIAN of each, and that was wrong in a way no test caught. The median
   * answers "where does a typical line begin and end", not "where is the column". A rental
   * agreement is largely short label/value rows, so on a real instrument the median said the column
   * was [128.7 .. 310.1] - 181pt - when the body margins are [113.4 .. 482.5]. Two 95pt signature
   * strips need 190pt, so the two signers' boxes were placed ON TOP OF EACH OTHER on every page.
   *
   * <p>Percentiles keep the robustness the median was chosen for, and actually find the edges. The
   * outliers being resisted are real: on the same document the extreme line start (56.7) and end
   * (539.3) both belong to the page footer, which the renderer prints OUTSIDE the body margins. p5
   * and p95 exclude that furniture and land on the body column itself.
   *
   * @throws IllegalStateException if the PDF cannot be parsed
   */
  static DocumentGeometry geometry(byte[] pdf) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      float width = Float.MAX_VALUE;
      float height = Float.MAX_VALUE;
      for (PDPage page : document.getPages()) {
        width = Math.min(width, displayedWidth(page));
        height = Math.min(height, displayedHeight(page));
      }
      if (width == Float.MAX_VALUE) {
        throw new IllegalStateException("Document has no pages; refusing to place a signature");
      }
      LineBounds bounds = new LineBounds();
      bounds.setSortByPosition(true);
      bounds.setStartPage(1);
      bounds.setEndPage(document.getNumberOfPages());
      bounds.writeText(document, new StringWriter());
      // A document with no text layer at all (a pure scan) has no measurable column; fall back to
      // the page's own proportions rather than refusing - the anchored block is what carries the
      // legal weight, and it is located by text that must exist for the request to proceed.
      float left = bounds.percentile(bounds.lineStarts, 5, width * 0.1f);
      float right = bounds.percentile(bounds.lineEnds, 95, width * 0.9f);
      return new DocumentGeometry(
          document.getNumberOfPages(), new AnchorPosition.PageBox(width, height), left, right);
    } catch (IOException e) {
      // Never echo document content in the fault - the instrument carries party PII.
      throw new IllegalStateException("Stamped document could not be parsed to measure its pages");
    }
  }

  /** Collects where each rendered line starts and ends, so the body column can be measured. */
  private static final class LineBounds extends PDFTextStripper {

    private final List<Float> lineStarts = new ArrayList<>();
    private final List<Float> lineEnds = new ArrayList<>();

    private LineBounds() throws IOException {}

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      float start = Float.MAX_VALUE;
      float end = -Float.MAX_VALUE;
      for (TextPosition position : textPositions) {
        String unicode = position.getUnicode();
        if (unicode == null || unicode.isBlank()) {
          continue;
        }
        start = Math.min(start, position.getXDirAdj());
        end = Math.max(end, position.getXDirAdj() + position.getWidthDirAdj());
      }
      if (start != Float.MAX_VALUE) {
        lineStarts.add(start);
        lineEnds.add(end);
      }
      super.writeString(text, textPositions);
    }

    /**
     * The {@code p}-th percentile of {@code values}, or {@code fallback} when there is no text to
     * measure. Nearest-rank, which needs no interpolation and cannot invent a value between two
     * observations.
     */
    private float percentile(List<Float> values, int p, float fallback) {
      if (values.isEmpty()) {
        return fallback;
      }
      List<Float> sorted = new ArrayList<>(values);
      Collections.sort(sorted);
      int index = Math.round((p / 100f) * (sorted.size() - 1));
      return sorted.get(Math.min(sorted.size() - 1, Math.max(0, index)));
    }
  }

  /**
   * The DISPLAYED width of a page. Deliberately NOT the raw media-box width: that is un-swapped for
   * rotation, and every coordinate here is expressed in displayed space.
   */
  private static float displayedWidth(PDPage page) {
    PDRectangle box = page.getCropBox();
    return quarterTurned(page) ? box.getHeight() : box.getWidth();
  }

  /** The DISPLAYED height of a page; see {@link #displayedWidth(PDPage)}. */
  private static float displayedHeight(PDPage page) {
    PDRectangle box = page.getCropBox();
    return quarterTurned(page) ? box.getWidth() : box.getHeight();
  }

  private static boolean quarterTurned(PDPage page) {
    int rotation = ((page.getRotation() % 360) + 360) % 360;
    return rotation == 90 || rotation == 270;
  }

  /** Accumulates one page's glyphs, then searches that page for each outstanding anchor. */
  private static final class Stripper extends PDFTextStripper {

    private final List<String> wanted;
    private final Map<String, AnchorPosition> found = new LinkedHashMap<>();
    private final StringBuilder pageText = new StringBuilder();
    private final List<TextPosition> pageGlyphs = new ArrayList<>();

    private Stripper(List<String> wanted) throws IOException {
      this.wanted = wanted;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      // Build a whitespace-free character stream with a parallel glyph index, so a match can be
      // traced back to the exact glyph that starts it regardless of how the run was split.
      for (TextPosition position : textPositions) {
        String unicode = position.getUnicode();
        if (unicode == null) {
          continue;
        }
        for (int i = 0; i < unicode.length(); i++) {
          char c = unicode.charAt(i);
          if (Character.isWhitespace(c)) {
            continue;
          }
          pageText.append(Character.toLowerCase(c));
          pageGlyphs.add(position);
        }
      }
      super.writeString(text, textPositions);
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
      String haystack = pageText.toString();
      // The DISPLAYED page size, which is what the direction-adjusted glyph coordinates are
      // expressed in. Deliberately NOT TextPosition.getPageWidth(): that reports the raw media-box
      // width, un-swapped for rotation, so mirroring a 90-degree-rotated page against it would
      // reflect x across the wrong axis length - and, like every placement bug here, silently.
      float pageWidth = displayedWidth(page);
      float pageHeight = displayedHeight(page);
      for (String anchor : wanted) {
        if (found.containsKey(anchor)) {
          continue; // first occurrence wins; a duplicated anchor is a template defect, not a choice
        }
        int index = haystack.indexOf(anchor.replaceAll("\\s+", ""));
        if (index < 0 || index >= pageGlyphs.size()) {
          continue;
        }
        TextPosition glyph = pageGlyphs.get(index);
        found.put(
            anchor,
            new AnchorPosition(
                getCurrentPageNo(),
                glyph.getXDirAdj(),
                // PDFBox reports y from the TOP (direction-adjusted); PDF's own origin is at the
                // bottom, and so is ZOOP's, so flip it here once.
                pageHeight - glyph.getYDirAdj(),
                pageWidth,
                pageHeight));
      }
      pageText.setLength(0);
      pageGlyphs.clear();
      super.endPage(page);
    }
  }
}

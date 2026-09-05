package in.agreementmitra.signing.zoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import in.agreementmitra.support.PageFurniture;
import in.agreementmitra.support.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the anchor-to-coordinate translation - the part of the ZOOP adapter most likely to
 * be silently wrong.
 *
 * <p><b>The mirrored x axis is the whole point.</b> ZOOP measures {@code x_coord} from the RIGHT
 * edge; PDF's native origin is bottom-LEFT. Getting that backwards places every signature in the
 * opposite margin and the provider reports no error at all. These tests pin the mirroring
 * explicitly, from both ends: an anchor near the left edge must produce a LARGE x, and one near the
 * right edge a SMALL one.
 *
 * <p>They are not, however, sufficient on their own - they assert our own arithmetic against
 * itself. The acceptance check for placement is a visual inspection of a real signed document
 * (design D4, task 8.3), which is why that is a manual gate.
 */
class EsignAnchorMappingTest {

  private static final String OWNER = "esign:owner";
  private static final String TENANT = "esign:tenant";

  // --- location --------------------------------------------------------------

  @Test
  void locatesEachAnchorOnItsOwnPageAtItsOwnPosition() {
    Map<String, AnchorPosition> found =
        EsignAnchorLocator.locate(TestPdfs.withEsignAnchors(), List.of(OWNER, TENANT));

    assertThat(found).containsOnlyKeys(OWNER, TENANT);
    assertThat(found.get(OWNER).pageNumber()).isEqualTo(1);
    assertThat(found.get(TENANT).pageNumber()).isEqualTo(1);
    // Drawn at x=60 (left) and x=width-160 (right); y=120 from the bottom in PDF's own convention.
    assertThat(found.get(OWNER).xFromLeft()).isCloseTo(60f, within(3f));
    assertThat(found.get(OWNER).yFromBottom()).isCloseTo(120f, within(6f));
    assertThat(found.get(TENANT).xFromLeft())
        .isCloseTo(PDRectangle.A4.getWidth() - 160f, within(3f));
  }

  @Test
  void findsAnchorsOnTheCorrectPageOfAMultiPageDocument() {
    // Stamp intake prepends the scanned certificate as page 1, shifting every page number.
    // Searching
    // the FINAL document is what makes that need no offset arithmetic: whatever page the anchor is
    // on IS the page the provider must sign.
    byte[] stamped = withBlankPagePrepended(TestPdfs.withEsignAnchors());

    Map<String, AnchorPosition> found = EsignAnchorLocator.locate(stamped, List.of(OWNER, TENANT));

    assertThat(found.get(OWNER).pageNumber()).isEqualTo(2);
    assertThat(found.get(TENANT).pageNumber()).isEqualTo(2);
    // ...and the position stays inside that page, so the signature cannot be placed off the sheet.
    for (AnchorPosition position : found.values()) {
      assertThat(position.xFromLeft()).isBetween(0f, position.pageWidth());
      assertThat(position.yFromBottom()).isBetween(0f, position.pageHeight());
      ZoopSignCoordinate coordinate = ZoopSignCoordinate.from(position);
      assertThat(coordinate.xCoord()).isBetween(0, Math.round(position.pageWidth()));
      assertThat(coordinate.yCoord()).isBetween(0, Math.round(position.pageHeight()));
    }
  }

  @Test
  void missingAnchorIsSimplyAbsentSoTheCallerCanRefuse() {
    Map<String, AnchorPosition> found =
        EsignAnchorLocator.locate(TestPdfs.withEsignAnchors(), List.of(OWNER, "esign:witness"));

    assertThat(found).containsOnlyKeys(OWNER);
    assertThat(found).doesNotContainKey("esign:witness");
  }

  @Test
  void aDocumentWithNoTextLayerYieldsNoAnchors() {
    assertThat(EsignAnchorLocator.locate(TestPdfs.singlePage(), List.of(OWNER))).isEmpty();
  }

  @Test
  void anUnparseableDocumentFailsWithoutEchoingItsContent() {
    assertThatThrownBy(() -> EsignAnchorLocator.locate("not a pdf".getBytes(), List.of(OWNER)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("eSign anchors");
  }

  // --- translation -----------------------------------------------------------

  @Test
  void xIsMirroredFromTheRightEdgeNotMeasuredFromTheLeft() {
    // The single most important assertion in this adapter. 595pt-wide page, anchor 60pt from the
    // LEFT -> ZOOP must be told 535pt from the RIGHT. If this ever reads 60, every signature lands
    // in the wrong margin and nothing errors.
    assertThat(ZoopSignCoordinate.mirrorX(595f, 60f)).isEqualTo(535);
    assertThat(ZoopSignCoordinate.mirrorX(595f, 535f)).isEqualTo(60);
  }

  @Test
  void mirroringIsSymmetricAcrossThePage() {
    float width = 612f; // US Letter, deliberately not A4
    for (float x : new float[] {0f, 100f, 306f, 500f, 612f}) {
      assertThat(ZoopSignCoordinate.mirrorX(width, x)).isEqualTo(Math.round(width - x));
    }
  }

  @Test
  void coordinatesStayOnThePage() {
    // Defensive clamping: a nonsense coordinate must not be forwarded to the vendor.
    assertThat(ZoopSignCoordinate.mirrorX(595f, -50f)).isEqualTo(595);
    assertThat(ZoopSignCoordinate.mirrorX(595f, 900f)).isZero();
  }

  @Test
  void translatedAnchorPutsTheLeftSideSignerFarFromTheRightEdge() {
    Map<String, AnchorPosition> found =
        EsignAnchorLocator.locate(TestPdfs.withEsignAnchors(), List.of(OWNER, TENANT));

    ZoopSignCoordinate owner = ZoopSignCoordinate.from(found.get(OWNER));
    ZoopSignCoordinate tenant = ZoopSignCoordinate.from(found.get(TENANT));

    assertThat(owner.pageNum()).isEqualTo(1);
    // Owner sits on the LEFT of the page, so its distance from the RIGHT edge is the larger one.
    assertThat(owner.xCoord()).isGreaterThan(tenant.xCoord());
    assertThat(owner.yCoord()).isCloseTo(120, within(6));
  }

  @Test
  void nonA4PageSizesAreHandledFromTheDocumentsOwnDimensions() {
    byte[] letter = TestPdfs.anchored(PDRectangle.LETTER, 0);

    AnchorPosition owner = EsignAnchorLocator.locate(letter, List.of(OWNER)).get(OWNER);

    assertThat(owner.pageWidth()).isCloseTo(PDRectangle.LETTER.getWidth(), within(1f));
    assertThat(ZoopSignCoordinate.from(owner).xCoord())
        .isEqualTo(
            Math.round(
                PDRectangle.LETTER.getWidth()
                    - (owner.xFromLeft() + ZoopSignCoordinate.BOX_WIDTH_PT)));
  }

  // --- box extent (task 5.1) -------------------------------------------------

  /**
   * The regression this change exists for. ZOOP positions the signature box's BOTTOM-RIGHT corner
   * and grows it left and up, so a coordinate handed straight from the anchor hangs the box over
   * whatever the template printed to the anchor's left. Placement must shift right by the box width
   * so the box's LEFT edge lands on the anchor.
   *
   * <p>Written as an explicit before/after: the naive value is what shipped and what put every
   * signature on the line below its signature area.
   */
  @Test
  void translationShiftsByTheBoxWidthSoTheBoxStartsAtTheAnchor() {
    AnchorPosition anchor = new AnchorPosition(1, 233.7f, 596.3f, 595f, 842f);

    ZoopSignCoordinate coordinate = ZoopSignCoordinate.from(anchor);

    int naive = Math.round(595f - 233.7f); // point-only translation: 361
    assertThat(coordinate.xCoord()).isNotEqualTo(naive);
    assertThat(coordinate.xCoord())
        .isEqualTo(Math.round(595f - (233.7f + ZoopSignCoordinate.BOX_WIDTH_PT)));
    // The box's left edge is what the anchor marks, so the drawn box starts there, not left of it.
    assertThat(595f - coordinate.xCoord() - ZoopSignCoordinate.BOX_WIDTH_PT)
        .isCloseTo(233.7f, within(1f));
    // y is untouched: the box grows UP from the baseline, which is where the anchor sits.
    assertThat(coordinate.yCoord()).isEqualTo(Math.round(596.3f));
  }

  @Test
  void anchorNearTheRightEdgeStaysOnThePageAfterTheShift() {
    AnchorPosition anchor = new AnchorPosition(1, 580f, 100f, 595f, 842f);

    ZoopSignCoordinate coordinate = ZoopSignCoordinate.from(anchor);

    assertThat(coordinate.xCoord()).isBetween(0, 595);
  }

  // --- every-page strip (task 5.2) -------------------------------------------

  private static DocumentGeometry a4(float contentLeft, float contentRight) {
    return new DocumentGeometry(
        5, new AnchorPosition.PageBox(595f, 842f), contentLeft, contentRight);
  }

  @Test
  void everyPageStripIsAlignedToTheBodyColumnNotThePaperEdge() {
    // The strips line up with the text a reader is already looking at: first signer's box starts at
    // the content's left edge, second signer's ends at its right edge.
    DocumentGeometry geometry = a4(114f, 482f);

    ZoopSignCoordinate first = ZoopSignCoordinate.everyPageFooter(geometry, 0, 2);
    ZoopSignCoordinate second = ZoopSignCoordinate.everyPageFooter(geometry, 1, 2);

    assertThat(595f - first.xCoord() - ZoopSignCoordinate.BOX_WIDTH_PT).isCloseTo(114f, within(1f));
    assertThat(595f - second.xCoord()).isCloseTo(482f, within(1f));
  }

  @Test
  void everyPageStripCarriesTheRealPageNumberNotTheAllPagesSentinel() {
    // The vendor's "all pages" number cannot exclude the page that already carries the block, so
    // pages are enumerated instead.
    ZoopSignCoordinate strip = ZoopSignCoordinate.everyPageFooter(a4(114f, 482f), 0, 3);

    assertThat(strip.pageNum()).isEqualTo(3);
  }

  @Test
  void everyPageStripsDoNotOverlapBetweenSigners() {
    DocumentGeometry geometry = a4(114f, 482f);

    ZoopSignCoordinate first = ZoopSignCoordinate.everyPageFooter(geometry, 0, 2);
    ZoopSignCoordinate second = ZoopSignCoordinate.everyPageFooter(geometry, 1, 2);

    float firstRight = 595f - first.xCoord();
    float secondLeft = 595f - second.xCoord() - ZoopSignCoordinate.BOX_WIDTH_PT;
    assertThat(firstRight).isLessThan(secondLeft);
    assertThat(first.yCoord()).isEqualTo(second.yCoord());
  }

  @Test
  void everyPageStripsDoNotOverlapEvenWhenTheColumnIsTooNarrowForBoth() {
    // THE CASE THAT SHIPPED. The test above passes a hand-picked 368pt column - wide enough that
    // the lanes could not collide whatever the code did. A real instrument measured [128.7, 310.1]:
    // 181pt, when two 95pt boxes need 190pt. The second signer's box was therefore placed on top of
    // the first on every page, and no test noticed because no test used a narrow column.
    DocumentGeometry geometry = a4(128.7f, 310.1f);

    ZoopSignCoordinate first = ZoopSignCoordinate.everyPageFooter(geometry, 0, 2);
    ZoopSignCoordinate second = ZoopSignCoordinate.everyPageFooter(geometry, 1, 2);

    float firstRight = 595f - first.xCoord();
    float secondLeft = 595f - second.xCoord() - ZoopSignCoordinate.BOX_WIDTH_PT;
    assertThat(secondLeft)
        .as("second signer's box must start after the first signer's box ends")
        .isGreaterThan(firstRight);
  }

  @Test
  void aColumnTooNarrowToSeatBothLanesStillKeepsBothOnThePage() {
    // Widening must not push a box off the sheet: overlapping is the thing being avoided, not the
    // only thing that matters.
    DocumentGeometry geometry = a4(400f, 430f);

    for (int lane = 0; lane < 2; lane++) {
      ZoopSignCoordinate strip = ZoopSignCoordinate.everyPageFooter(geometry, lane, 2);
      assertThat(strip.xCoord()).isBetween(0, 595);
    }
  }

  @Test
  void theBodyColumnIsMeasuredFromTheColumnEdgesNotTheTypicalLine() throws Exception {
    // A document shaped like a real agreement: many SHORT label/value lines plus a few full-width
    // ones, and a footer printed outside the body margins. The median line start/end describes the
    // short rows and badly understates the column; the column edges are what a signature strip has
    // to line up with.
    byte[] pdf = documentWithShortRowsAndAFooter();

    DocumentGeometry geometry = EsignAnchorLocator.geometry(pdf);

    // The body runs 120..480. The median line would report roughly 120..260 (the short rows).
    assertThat(geometry.contentRight() - geometry.contentLeft())
        .as("measured column must span the body, not the typical short line")
        .isGreaterThan(2 * ZoopSignCoordinate.BOX_WIDTH_PT);
    assertThat(geometry.contentLeft()).isCloseTo(120f, within(12f));
    assertThat(geometry.contentRight()).isCloseTo(480f, within(12f));
    // ...and the footer, printed outside the body margins at 60 and 540, is excluded as furniture.
    assertThat(geometry.contentLeft()).isGreaterThan(60f);
    assertThat(geometry.contentRight()).isLessThan(540f);
  }

  /**
   * Twenty short rows (120..260), four full-width lines (120..480), and one footer line spanning
   * 60..540 outside the body margins - the shape that defeated the median.
   */
  private static byte[] documentWithShortRowsAndAFooter() throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int i = 0; i < 20; i++) {
          write(cs, font, 120f, 700f - i * 18f, 140f, "Label and value");
        }
        for (int i = 0; i < 4; i++) {
          write(cs, font, 120f, 320f - i * 18f, 360f, "A full width clause line of body text");
        }
        write(cs, font, 60f, 30f, 480f, "FOOTER REFERENCE AND URL OUTSIDE THE BODY MARGINS");
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** Draw {@code text} starting at {@code x}, scaled so the run is {@code width} points wide. */
  private static void write(
      PDPageContentStream cs, PDType1Font font, float x, float y, float width, String text)
      throws Exception {
    float natural = font.getStringWidth(text) / 1000f * 10f;
    float size = 10f * (width / natural);
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText(text);
    cs.endText();
  }

  @Test
  void everyPageStripFitsBetweenTheFooterAndTheBodyText() {
    // The strip has to clear TWO things, and the old version of this test only knew about one.
    // Below it: the renderer's tracking footer, the bottom ~16pt of the sheet. Above it: the body
    // text, whose floor is the 68.4pt bottom margin the renderer reserves. The box grows UP from
    // the submitted coordinate, so it is the TOP edge that has to stay under the content floor -
    // and that is the assertion whose absence let every page print a signature over its own text.
    ZoopSignCoordinate strip = ZoopSignCoordinate.everyPageFooter(a4(114f, 482f), 0, 2);

    // Both bounds come from PageFurniture, the one place the two modules' shared band is written
    // down -- so shrinking the renderer's margin breaks this test instead of a real document.
    assertThat(strip.yCoord()).isGreaterThanOrEqualTo(Math.round(PageFurniture.FOOTER_BAND_TOP_PT));
    assertThat(strip.yCoord() + ZoopSignCoordinate.BOX_HEIGHT_PT)
        .isLessThan(PageFurniture.CONTENT_FLOOR_PT);
  }

  @Test
  void everyPageStripStaysOnThePageForA4AndForLetter() {
    for (PDRectangle size : List.of(PDRectangle.A4, PDRectangle.LETTER)) {
      // A deliberately wide content column: the clamp, not the caller, keeps the box on the sheet.
      DocumentGeometry geometry =
          new DocumentGeometry(
              3,
              new AnchorPosition.PageBox(size.getWidth(), size.getHeight()),
              20f,
              size.getWidth() - 5f);
      for (int lane : new int[] {0, 1}) {
        ZoopSignCoordinate strip = ZoopSignCoordinate.everyPageFooter(geometry, lane, 1);

        assertThat(strip.xCoord()).isBetween(0, Math.round(size.getWidth()));
        assertThat(strip.yCoord()).isBetween(0, Math.round(size.getHeight()));
        assertThat(strip.yCoord() + ZoopSignCoordinate.BOX_HEIGHT_PT).isLessThan(size.getHeight());
      }
    }
  }

  @Test
  void aThirdSignerWrapsALaneRatherThanLandingOffPage() {
    DocumentGeometry geometry = a4(114f, 482f);

    assertThat(ZoopSignCoordinate.everyPageFooter(geometry, 2, 2).xCoord())
        .isEqualTo(ZoopSignCoordinate.everyPageFooter(geometry, 0, 2).xCoord());
  }

  @Test
  void geometryMeasuresTheSmallestPageAndTheBodyColumn() {
    // A stamp certificate scanned at a different size than the agreement is the normal case, and
    // one strip position has to clear the SHORT page.
    byte[] mixed = withBlankPagePrepended(TestPdfs.anchored(PDRectangle.A5, 0));

    DocumentGeometry geometry = EsignAnchorLocator.geometry(mixed);

    assertThat(geometry.pageCount()).isEqualTo(2);
    assertThat(geometry.smallestPage().width()).isLessThanOrEqualTo(PDRectangle.A5.getWidth());
    assertThat(geometry.contentLeft()).isLessThan(geometry.contentRight());
    ZoopSignCoordinate strip = ZoopSignCoordinate.everyPageFooter(geometry, 1, 1);
    assertThat(strip.xCoord()).isBetween(0, Math.round(geometry.smallestPage().width()));
  }

  private static byte[] withBlankPagePrepended(byte[] pdf) {
    try (PDDocument document = Loader.loadPDF(pdf);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      document
          .getPages()
          .insertBefore(new org.apache.pdfbox.pdmodel.PDPage(PDRectangle.A4), document.getPage(0));
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

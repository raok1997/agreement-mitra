package in.agreementmitra.signing.zoop;

/**
 * One signature placement in ZOOP eSign v5's coordinate system.
 *
 * <p><b>Read this twice.</b> ZOOP measures coordinates from the page's <b>bottom-RIGHT</b> corner:
 * {@code x_coord} is the distance from the <b>right</b> edge, increasing leftward, while {@code
 * y_coord} is the distance from the bottom, as PDF does. PDF's native origin is bottom-<b>LEFT</b>,
 * so the horizontal axis must be <b>mirrored</b>: {@code x_coord = page_width - pdfbox_x}.
 *
 * <p>Getting that backwards puts every signature in the opposite margin <b>with no error at all</b>
 * - the provider accepts the request, the document is signed, and the defect is only visible by
 * looking at the rendered page. That is why the mirroring is a one-line pure function with its own
 * unit test, and why the acceptance check for it is a visual inspection of a real signed document
 * (design D4), not an assertion over our own arithmetic.
 *
 * <p><b>The coordinate positions the box's BOTTOM-RIGHT corner, not a point.</b> The signature box
 * is drawn growing LEFT and UP from the submitted coordinate. So a coordinate handed straight from
 * an anchor hangs the whole box up-and-left of that anchor - which is exactly how every signature
 * once landed on the line below its signature area.
 *
 * <p>The two axes are compensated DIFFERENTLY, and this used to claim otherwise. Horizontally,
 * every placement shifts by {@link #BOX_WIDTH_PT} so the box's left edge lands on the anchor.
 * Vertically it does not, and must not: the template puts the anchor at the FOOT of an empty
 * signature area, so growing up by {@link #BOX_HEIGHT_PT} is what fills that area. What the box
 * height does constrain is the every-page strip, whose band has to be tall enough to hold it - see
 * {@link #FOOTER_Y_PT}, where getting that wrong printed a signature over the body text.
 *
 * <p>{@code page_num} semantics per the vendor: {@code 0} = all pages, {@code -1} = last page,
 * {@code >= 1} = that page. An anchored placement emits a concrete page number; the every-page
 * strip emits {@code 0}.
 *
 * @param pageNum 1-based page number, or {@code 0} for every page
 * @param xCoord distance from the RIGHT edge, in points
 * @param yCoord distance from the BOTTOM edge, in points
 */
record ZoopSignCoordinate(int pageNum, int xCoord, int yCoord) {

  /** The vendor's "every page" page number. */
  private static final int ALL_PAGES = 0;

  /*
   * BOX GEOMETRY -- MEASURED, NOT DOCUMENTED.
   *
   * ZOOP's v5 coordinate documentation is not published (their V4 Confluence page 404s), so these
   * come from a calibration run, not from a vendor spec:
   *
   *   observed 2026-08-28, ZOOP test host, viewer v4.2.0, group_id 6a91225e491affca87286192
   *   a synthetic A4 grid page with probes submitted at (150,500) and (400,300) in PDF coordinates
   *   -> the drawn box's BOTTOM-RIGHT corner landed exactly on the submitted point, and the box
   *      measured ~95pt wide by ~40pt tall against the printed 100pt grid.
   *
   * Re-run that calibration whenever the provider's API or viewer version changes: a box resize on
   * their side moves every signature on every instrument, and nothing in the response would say so.
   */
  static final float BOX_WIDTH_PT = 95f;

  static final float BOX_HEIGHT_PT = 40f;

  /**
   * Clear space demanded between the two signers' strips. Small on purpose: it exists to stop the
   * boxes touching, not to impose a layout - the body column decides where they actually sit.
   */
  private static final float MIN_LANE_GAP_PT = 8f;

  /**
   * How far above the bottom edge the every-page strip's BOTTOM edge sits, in points.
   *
   * <p><b>This is a band, and the box grows UP out of it.</b> The submitted coordinate is the box's
   * bottom-right corner, so a strip at {@code y} occupies {@code y} to {@code y +} {@link
   * #BOX_HEIGHT_PT}. At 26pt that is 26-66pt from the bottom edge: clear of the renderer's tracking
   * footer below (baseline measured at 16.2pt, glyphs reaching ~22pt) and clear of the body text
   * above, whose lowest line measured 77.7pt across a four-page render.
   *
   * <p>It used to be 55pt, which put the box at 55-95pt, overlapping the body text by some 17pt -
   * so every page printed a signature across its own last lines. The old value was not arbitrary: a
   * test asserted the strip sat "above the footer band" and assumed that band was 40pt tall, so the
   * strip was pushed up into the text to satisfy it. Nothing asserted the thing that actually
   * mattered - that the strip clears the CONTENT.
   *
   * <p>This value and where the renderer actually stops printing have to agree. They are in
   * different modules on purpose (layout belongs to {@code documents}), so the agreement is
   * enforced from both ends against {@code PageFurniture}: a real render asserts the band is empty,
   * and this adapter asserts the strip lands inside it.
   */
  private static final float FOOTER_Y_PT = 26f;

  /**
   * Translate a located anchor into ZOOP's convention: mirror the x axis, then shift right by the
   * box width so the box's LEFT edge -- not its right edge -- lands on the anchor.
   *
   * <p>Without that shift the box hangs to the LEFT of the anchor and covers whatever the template
   * printed there. The anchor marks where the signature starts; the vendor positions where it ends.
   */
  static ZoopSignCoordinate from(AnchorPosition anchor) {
    return new ZoopSignCoordinate(
        anchor.pageNumber(),
        mirrorX(anchor.pageWidth(), anchor.xFromLeft() + BOX_WIDTH_PT),
        Math.round(clamp(anchor.yFromBottom(), anchor.pageHeight())));
  }

  /**
   * The strip for one page, for the signer in the given lane.
   *
   * <p>This is the one placement with no anchor behind it (design D5): there is no per-page token
   * to locate, so the position is derived from the document's own body column. Lane 0 is
   * <b>left-aligned to the content column</b> and lane 1 <b>right-aligned</b> to it, so the strips
   * line up with the text a reader is already looking at rather than floating in the paper margin.
   *
   * <p>Both are expressed as the box's RIGHT edge, because that is the corner the vendor positions.
   * Clamped so a narrow content column cannot push a box off the sheet.
   *
   * @param geometry the measured instrument
   * @param lane the signer's ordinal; lanes wrap so two signers never share one
   * @param pageNum the page this strip goes on
   */
  static ZoopSignCoordinate everyPageFooter(DocumentGeometry geometry, int lane, int pageNum) {
    float pageWidth = geometry.smallestPage().width();
    float left = geometry.contentLeft();
    float right = seatBothLanes(left, geometry.contentRight(), pageWidth);
    float rightEdgeFromLeft = Math.floorMod(lane, 2) == 0 ? left + BOX_WIDTH_PT : right;
    return new ZoopSignCoordinate(
        pageNum,
        mirrorX(pageWidth, clamp(rightEdgeFromLeft, pageWidth)),
        Math.round(clamp(FOOTER_Y_PT, geometry.smallestPage().height())));
  }

  /**
   * The right edge to hang lane 1 on, widened if the measured column cannot seat both boxes.
   *
   * <p>Lane 0 occupies {@code left} to {@code left + }{@link #BOX_WIDTH_PT} and lane 1 ends on the
   * returned edge, so the two only clear each other if the span is at least two boxes plus a gap. A
   * column narrower than that put one signer's box on top of the other's - the spec requires the
   * lanes not to overlap, and before this the only thing standing between a document and that
   * defect was the column measurement happening to come out wide enough.
   *
   * <p>Widening runs rightwards from lane 0, bounded by the page: the strips are meant to sit under
   * the text, so lane 0 stays anchored to the body column and only lane 1 moves out. If even the
   * page cannot seat both, the page edge is returned - overlapping is worse than a strip near the
   * margin, and {@link #clamp} still keeps it on the sheet.
   */
  private static float seatBothLanes(float left, float measuredRight, float pageWidth) {
    float minimumRight = left + 2 * BOX_WIDTH_PT + MIN_LANE_GAP_PT;
    if (measuredRight >= minimumRight) {
      return measuredRight;
    }
    return Math.min(Math.max(minimumRight, measuredRight), pageWidth);
  }

  /**
   * The mirrored horizontal coordinate: {@code page_width - x_from_left}, clamped into the page.
   * Extracted as a named function precisely because it is the single most likely thing in this
   * adapter to be wrong, and a wrong value fails silently.
   */
  static int mirrorX(float pageWidth, float xFromLeft) {
    return Math.round(clamp(pageWidth - xFromLeft, pageWidth));
  }

  /**
   * Keep a coordinate on the page; a negative or over-wide value would be nonsense to the vendor.
   */
  private static float clamp(float value, float max) {
    if (value < 0f) {
      return 0f;
    }
    return max > 0f && value > max ? max : value;
  }
}

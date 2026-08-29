package in.agreementmitra.signing.zoop;

/**
 * Where an {@code esign:<role>} anchor was found in the stamped PDF, in <b>PDF's own</b> coordinate
 * convention: page numbers 1-based, x measured from the LEFT edge, y measured from the BOTTOM edge,
 * in points.
 *
 * <p>Kept deliberately vendor-neutral - it is the raw fact about the document, before any
 * provider's coordinate convention is applied. The mirroring into ZOOP's right-edge origin happens
 * in {@link ZoopSignCoordinate}, so the arithmetic that is easy to get wrong lives in exactly one
 * place with its own test.
 *
 * <p>{@code pageWidth}/{@code pageHeight} are the <b>rotation-adjusted</b> dimensions of the page
 * the anchor sits on, so a rotated or non-A4 page needs no special-casing downstream.
 *
 * @param pageNumber 1-based page number within the stamped PDF
 * @param xFromLeft distance from the page's left edge, in points
 * @param yFromBottom distance from the page's bottom edge, in points
 * @param pageWidth rotation-adjusted page width, in points
 * @param pageHeight rotation-adjusted page height, in points
 */
record AnchorPosition(
    int pageNumber, float xFromLeft, float yFromBottom, float pageWidth, float pageHeight) {

  /**
   * A page's rotation-adjusted dimensions, with no anchor attached.
   *
   * <p>Needed by the every-page placement, which has no anchor to derive a position from: it is a
   * margin offset, and the margin has to be measured against a page.
   *
   * @param width rotation-adjusted page width, in points
   * @param height rotation-adjusted page height, in points
   */
  record PageBox(float width, float height) {}
}

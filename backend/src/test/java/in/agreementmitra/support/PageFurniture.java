package in.agreementmitra.support;

/**
 * The reserved band at the foot of every rendered page, as a contract between the two modules that
 * share it.
 *
 * <p><b>Why this exists.</b> Two things live below the body text: the {@code documents} renderer's
 * tracking footer, and the per-page eSign signature strip that {@code signing} asks the provider to
 * draw. Neither module can see the other's geometry - layout belongs to {@code documents}, and
 * placement belongs to the provider adapter - so for a while nothing checked that they fit
 * together. They did not: the strip was placed 55-95pt from the bottom edge while the content floor
 * was 43.2pt, so every page printed a signature across its own last lines of text.
 *
 * <p>The numbers therefore live here, in test support, referenced from BOTH sides:
 *
 * <ul>
 *   <li>{@code documents} asserts that no rendered glyph lands in the strip band;
 *   <li>{@code signing} asserts that the strip it places lands inside it.
 * </ul>
 *
 * <p>A change to either module's constants that breaks the arrangement fails a test instead of
 * quietly shipping a signature over a clause - which is the only way this class of defect is ever
 * caught, since the provider accepts a bad placement without complaint and the document renders
 * without error.
 */
public final class PageFurniture {

  /**
   * Top of the renderer's tracking-footer band, in points from the page's bottom edge.
   *
   * <p><b>Measured, not estimated.</b> A first pass at this guessed 16pt and the band test failed
   * immediately, reporting dozens of glyphs at exactly {@code y=16} - the footer's own baseline. So
   * the footer line sits ON 16pt, and its ascenders reach roughly 22pt; 24pt clears it. Guessing a
   * furniture height is how the strip ended up in the text column in the first place, which is why
   * this number now comes from a render that was actually looked at.
   */
  public static final float FOOTER_BAND_TOP_PT = 24f;

  /**
   * The body text floor, in points from the page's bottom edge. No body glyph may sit below this on
   * any page.
   *
   * <p><b>Measured, and lower than the renderer's own margin setting.</b> A probe render of the
   * seeded Telangana template reported the lowest body line at 77.7pt (page 1 of 4; the other pages
   * stopped at 84pt and 96pt) while the footer sat at 16.2pt. The body column is bounded by the
   * template's CSS, not by the Gotenberg margin parameter - raising that parameter was tried and
   * changed nothing, so the renderer needs no change to make room here.
   *
   * <p>72pt is deliberately set BELOW the observed 77.7pt: where the last line falls depends on
   * where the page happens to break, so the assertion is against a floor the renderer is
   * comfortably inside rather than against one particular render's luckiest line.
   */
  public static final float CONTENT_FLOOR_PT = 72f;

  private PageFurniture() {}
}

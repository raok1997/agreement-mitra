package in.agreementmitra.documents;

/**
 * The public HTML-to-PDF seam of the {@code documents} module: hand it a self-contained HTML string
 * and it returns the rendered PDF bytes. The only render entry point another package may depend on
 * -- its Gotenberg-backed implementation is package-private, so a caller in {@code
 * documents.template} (the projection service) can turn the compiler's HTML into a PDF without
 * reaching a package-private client across packages.
 *
 * <p>The HTML must be self-contained (fonts embedded, no external URLs): the implementation renders
 * through the offline Gotenberg leg with Chromium's outbound network denied, so no value in the
 * HTML can trigger an outbound request. Neither the HTML nor the PDF bytes are ever logged.
 */
public interface HtmlPdfRenderer {

  /**
   * Render {@code html} to PDF bytes through the offline, network-denied Gotenberg leg, stamping a
   * per-page footer in the reserved bottom margin: the {@code reference} (the tracking number or a
   * preview marker) with the platform URL, and a "Page X of Y" indicator. The render emulates print
   * media, so a screen-only body element (the on-screen provenance line) does not appear in the
   * PDF.
   *
   * @param html a self-contained HTML document (no external references)
   * @param reference the non-PII footer reference (tracking number or preview marker)
   * @return the rendered PDF bytes (begin with the {@code %PDF-} signature)
   * @throws DocumentRenderException if the render fails or returns no document
   */
  byte[] toPdf(String html, String reference);
}

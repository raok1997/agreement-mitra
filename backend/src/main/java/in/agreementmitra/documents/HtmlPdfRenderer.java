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
   * Render {@code html} to PDF bytes through the offline, network-denied Gotenberg leg.
   *
   * @param html a self-contained HTML document (no external references)
   * @return the rendered PDF bytes (begin with the {@code %PDF-} signature)
   * @throws DocumentRenderException if the render fails or returns no document
   */
  byte[] toPdf(String html);

  /**
   * Render {@code html} to PDF, stamping optional non-PII document furniture: a {@code
   * documentReference} and a "page X of Y" indicator in the page footer (design D4). This is the
   * only abstract method's furniture-aware sibling: it is a {@code default} so the interface stays
   * functional and existing implementations need not change; the Gotenberg-backed implementation
   * overrides it to attach the footer. A {@code null}/blank reference means "no furniture" and
   * falls back to the plain {@link #toPdf(String)} render.
   *
   * @param html a self-contained HTML document (no external references)
   * @param documentReference an optional, non-PII reference to stamp in the footer (may be null)
   * @return the rendered PDF bytes
   * @throws DocumentRenderException if the render fails or returns no document
   */
  default byte[] toPdf(String html, String documentReference) {
    return toPdf(html);
  }
}

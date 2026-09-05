package in.agreementmitra.documents;

import org.springframework.stereotype.Component;

/**
 * The {@link HtmlPdfRenderer} implementation over the package-private {@link GotenbergClient}. A
 * thin adapter: it adds no policy of its own -- it hands the already self-contained, compiled HTML
 * straight to Gotenberg -- so the offline / Chromium-network-denied / never-log guarantees of the
 * Gotenberg leg are preserved byte-for-byte. Package-private; the module exposes only the {@link
 * HtmlPdfRenderer} interface.
 */
@Component
class GotenbergHtmlPdfRenderer implements HtmlPdfRenderer {

  private final GotenbergClient gotenbergClient;

  GotenbergHtmlPdfRenderer(GotenbergClient gotenbergClient) {
    this.gotenbergClient = gotenbergClient;
  }

  @Override
  public byte[] toPdf(String html, String reference) {
    return gotenbergClient.renderHtml(html, reference);
  }
}

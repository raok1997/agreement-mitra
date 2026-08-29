package in.agreementmitra.documents;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

/**
 * Thin HTTP client for Gotenberg's Chromium HTML-to-PDF route. POSTs the self-contained HTML as the
 * multipart {@code index.html} and returns the PDF bytes. Package-private -- internal to the {@code
 * documents} module.
 *
 * <p>The app holds no browser: each render is one HTTP call to Gotenberg, which owns Chromium's
 * lifecycle and (per compose config) denies Chromium's outbound network -- the SSRF/exfil guard.
 * The app bounds concurrent renders with a {@link Semaphore} and relies on the client's read
 * timeout to fail a stuck render cleanly. <b>Neither the HTML nor the PDF bytes are ever
 * logged.</b>
 */
@Component
class GotenbergClient {

  /** Gotenberg's Chromium HTML-to-PDF route; the main file must be named {@code index.html}. */
  private static final String HTML_ROUTE = "/forms/chromium/convert/html";

  // A4 portrait, in inches (Gotenberg's paper units).
  private static final String A4_WIDTH_IN = "8.27";
  private static final String A4_HEIGHT_IN = "11.69";

  // Vertical margin (inches) reserved for the footer band when document furniture is stamped.
  private static final String FURNITURE_MARGIN_IN = "0.6";

  // An empty header suppresses Chromium's default header (date/title) when we supply a footer.
  private static final String EMPTY_HEADER =
      "<html><head><style>*{margin:0;padding:0;}</style></head><body></body></html>";

  private final RestClient restClient;
  private final Semaphore renderPermits;
  private final String platformUrl;

  GotenbergClient(
      RestClient gotenbergRestClient,
      GotenbergProperties properties,
      DocumentFooterProperties footerProperties) {
    this.restClient = gotenbergRestClient;
    this.renderPermits = new Semaphore(properties.maxConcurrentRenders());
    this.platformUrl = footerProperties.platformUrl();
  }

  /**
   * Render {@code html} to PDF bytes via Gotenberg. The HTML must be self-contained (no external
   * references are fetched -- Gotenberg denies Chromium's outbound network). The render emulates
   * <b>print</b> media, and a footer band is stamped in the reserved bottom margin on <b>every</b>
   * page: the escaped {@code reference} (the tracking number or a preview marker) with the platform
   * URL on the left, and a "Page X of Y" indicator on the right. Rendering the footer as furniture
   * in the margin (not as body flow) keeps it off its own page and clear of the content; the
   * on-screen provenance line is a screen-only body element that print media hides. The reference
   * is non-PII; it and the URL are HTML-escaped defensively; the HTML/PDF are never logged.
   *
   * @throws DocumentRenderException if Gotenberg is unreachable/times out or returns no document
   */
  byte[] renderHtml(String html, String reference) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("files", namedHtml(html, "index.html")); // Gotenberg's required main-file name
    form.add("paperWidth", A4_WIDTH_IN);
    form.add("paperHeight", A4_HEIGHT_IN);
    // Print media so a screen-only body element (the on-screen provenance line) does not appear in
    // the PDF; empty header + our footer reserve the bottom band and suppress Chromium's defaults;
    // the footer is stamped on every page (reference + URL + page number).
    form.add("emulatedMediaType", "print");
    form.add("files", namedHtml(EMPTY_HEADER, "header.html"));
    form.add("files", namedHtml(footerHtml(reference), "footer.html"));
    form.add("marginTop", FURNITURE_MARGIN_IN);
    form.add("marginBottom", FURNITURE_MARGIN_IN);

    boolean acquired = false;
    try {
      renderPermits.acquire();
      acquired = true;
      byte[] pdf =
          restClient
              .post()
              .uri(HTML_ROUTE)
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(form)
              .retrieve()
              .body(byte[].class);
      if (pdf == null || pdf.length == 0) {
        throw new DocumentRenderException("Gotenberg returned an empty document");
      }
      return pdf;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DocumentRenderException("render interrupted while waiting for a render slot", e);
    } catch (RestClientException e) {
      // Message only -- no HTML/PDF content, no response body echoed.
      throw new DocumentRenderException("Gotenberg render failed: " + e.getMessage(), e);
    } finally {
      if (acquired) {
        renderPermits.release();
      }
    }
  }

  /**
   * A UTF-8 multipart part carrying {@code content} under the Gotenberg-required {@code filename}.
   */
  private static ByteArrayResource namedHtml(String content, String filename) {
    return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  /**
   * The footer template: the escaped {@code reference} and platform URL on the left, and Chromium's
   * {@code pageNumber} / {@code totalPages} placeholders on the right, on every page.
   *
   * <p><b>"Agreement page X of Y", not "Page X of Y".</b> This footer is stamped while the
   * agreement is rendered, before the e-stamp certificate is bound in front of it as page 1 - so
   * the count can only ever describe the agreement. Left as a bare "Page X of Y" it read as a claim
   * about the file, and a five-page document declaring four pages is the sort of discrepancy a
   * registrar or a bank counts sheets to find. Naming what is being counted makes it true again.
   * Chromium renders header/footer templates with a zeroed font by default, so the size is set
   * inline. The reference is system-generated and non-PII but is escaped defensively (markup/data
   * discipline); the URL is inert display text (no anchor, never fetched -- the render stays
   * offline). A blank platform URL renders the reference alone.
   *
   * <p>Package-private (not {@code private}) so a same-package unit test can assert the layout,
   * escaping, and the page-number placeholders without a Gotenberg round-trip.
   */
  String footerHtml(String reference) {
    String ref = HtmlUtils.htmlEscape(reference == null ? "" : reference, "UTF-8");
    String left =
        platformUrl.isBlank()
            ? ref
            : ref + " &middot; " + HtmlUtils.htmlEscape(platformUrl, "UTF-8");
    // A table layout (not flex): Chromium reliably fills the .pageNumber / .totalPages placeholders
    // in a table-based footer, whereas a flex row can leave .totalPages blank. font-size is inline
    // (Chromium zeroes the header/footer font by default).
    return "<html><head><style>*{margin:0;padding:0;}"
        + "td{font-family:Georgia,serif;font-size:8px;color:#666;white-space:nowrap;}</style></head>"
        + "<body style=\"width:100%;\">"
        + "<table style=\"width:100%;border-collapse:collapse;\"><tr>"
        + "<td style=\"text-align:left;padding-left:20mm;\">"
        + left
        + "</td>"
        + "<td style=\"text-align:right;padding-right:20mm;\">"
        + "Agreement page <span class=\"pageNumber\"></span> of"
        + " <span class=\"totalPages\"></span>"
        + "</td>"
        + "</tr></table></body></html>";
  }
}

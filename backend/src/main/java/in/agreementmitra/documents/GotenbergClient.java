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

  GotenbergClient(RestClient gotenbergRestClient, GotenbergProperties properties) {
    this.restClient = gotenbergRestClient;
    this.renderPermits = new Semaphore(properties.maxConcurrentRenders());
  }

  /**
   * Render {@code html} to PDF bytes via Gotenberg. The HTML must be self-contained (no external
   * references are fetched -- Gotenberg denies Chromium's outbound network). When {@code
   * documentReference} is non-null, a footer band carrying that reference and a "Page X of Y"
   * indicator is stamped on every page (document furniture, design D4); a null reference renders
   * the bare document unchanged. The reference is system-generated and non-PII; it is HTML-escaped
   * defensively and never logged.
   *
   * @throws DocumentRenderException if Gotenberg is unreachable/times out or returns no document
   */
  byte[] renderHtml(String html, String documentReference) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("files", namedHtml(html, "index.html")); // Gotenberg's required main-file name
    form.add("paperWidth", A4_WIDTH_IN);
    form.add("paperHeight", A4_HEIGHT_IN);
    if (documentReference != null) {
      // Empty header + our footer: reserve the top/bottom band and suppress Chromium's defaults.
      form.add("files", namedHtml(EMPTY_HEADER, "header.html"));
      form.add("files", namedHtml(footerHtml(documentReference), "footer.html"));
      form.add("marginTop", FURNITURE_MARGIN_IN);
      form.add("marginBottom", FURNITURE_MARGIN_IN);
    }

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
   * The footer template: the escaped document reference on the left and Chromium's {@code
   * pageNumber} / {@code totalPages} placeholders ("Page X of Y") on the right. Chromium renders
   * header/footer templates with a zeroed font by default, so the size is set inline. The reference
   * is system-generated and non-PII but is escaped defensively (markup/data discipline).
   */
  private static String footerHtml(String documentReference) {
    String ref = HtmlUtils.htmlEscape(documentReference, "UTF-8");
    return "<html><head><style>*{margin:0;padding:0;}</style></head>"
        + "<body style=\"font-family:Georgia,serif;font-size:8px;color:#666;width:100%;"
        + "padding:0 20mm;\">"
        + "<div style=\"display:flex;justify-content:space-between;width:100%;\">"
        + "<span>"
        + ref
        + "</span>"
        + "<span>Page <span class=\"pageNumber\"></span> of <span class=\"totalPages\"></span></span>"
        + "</div></body></html>";
  }
}

package in.agreementmitra.documents.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless document-projection HTTP surface: {@code POST /api/templates/document/preview}. Accepts
 * a partial working-set data map (optionally with {@code dimensions}), resolves the effective
 * template, validates (preview tier), compiles, and returns the result content-negotiated on {@code
 * Accept}: {@code text/html} -> the compiled escaped HTML for the live pane (embed in a sandboxed
 * iframe); anything else -> the Gotenberg PDF (Download PDF).
 *
 * <p><b>Persists nothing</b> -- no agreement row, no draft, no blob: this route renders a transient
 * working set. Both variants are {@code Cache-Control: no-store} (the document carries party PII);
 * the HTML variant adds an iframe-safe CSP. Missing fields render placeholders (preview-tier
 * validation); an out-of-bounds value is rejected via the RFC 9457 contract before any document is
 * drawn, echoing no submitted value. Neither the rendered bytes/HTML nor any submitted value is
 * ever logged (the body is never written to a log).
 *
 * <p>SECURITY: anonymous like the rest of the render surface and the cheapest render to abuse (no
 * persisted-agreement precondition). The HTML variant is the hot path (no Gotenberg); server-side
 * validation caps payloads by the schema. Per-caller rate-limiting is an owed companion control
 * before this leaves sandbox (tracked with the other deferred pre-prod controls).
 */
@RestController
class DocumentProjectionController {

  // CSP for the HTML variant: it is embedded in a sandboxed iframe and must never reach the
  // network.
  // Deny everything, allow only inline styles + data-URI fonts/images (our compiled template's
  // shape). No script-src: the template has no scripts and none may run, so escaped party data can
  // never execute. Paired with the framework's nosniff default and Cache-Control: no-store.
  private static final String PREVIEW_HTML_CSP =
      "default-src 'none'; style-src 'unsafe-inline'; font-src data:; img-src data:;"
          + " base-uri 'none'; form-action 'none'";

  private final DocumentProjectionApi documentProjection;

  DocumentProjectionController(DocumentProjectionApi documentProjection) {
    this.documentProjection = documentProjection;
  }

  @PostMapping("/api/templates/document/preview")
  ResponseEntity<byte[]> preview(
      @RequestBody DocumentProjectionRequest request,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
    if (wantsHtml(accept)) {
      String html = documentProjection.previewHtml(request);
      return ResponseEntity.ok()
          .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.inline().filename("document.html").build().toString())
          .cacheControl(CacheControl.noStore())
          .header("Content-Security-Policy", PREVIEW_HTML_CSP)
          .body(html.getBytes(StandardCharsets.UTF_8));
    }
    byte[] pdf = documentProjection.previewPdf(request);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename("document.pdf").build().toString())
        .cacheControl(CacheControl.noStore())
        .body(pdf);
  }

  /** True when the client explicitly asks for {@code text/html} (the live-pane variant). */
  private static boolean wantsHtml(String accept) {
    return accept != null && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_HTML_VALUE);
  }
}

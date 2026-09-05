package in.agreementmitra.documents.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for the template catalog:
 *
 * <ul>
 *   <li>{@code GET /api/templates?state=..&type=..&q=..} -- the filtered <b>published</b> browse
 *       list (possibly empty);
 *   <li>{@code GET /api/templates/{id}} -- one published entry, or {@code 404} (RFC 9457, input not
 *       echoed) for an unknown <b>or</b> non-published id -- the same response for both, so a
 *       draft/deprecated entry's existence cannot be probed.
 * </ul>
 *
 * <p>The bodies are deterministic, non-PII system metadata. Only DTOs cross the boundary; there is
 * no client-settable id or status field, and no authoring surface (reads only).
 */
@RestController
class TemplateCatalogController {

  private final TemplateCatalogApi catalog;

  TemplateCatalogController(TemplateCatalogApi catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/templates")
  List<TemplateSummary> list(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String q) {
    return catalog.list(state, type, q);
  }

  @GetMapping("/api/templates/{id}")
  TemplateDetail detail(@PathVariable String id) {
    return catalog.detail(id); // 404 (ResourceNotFoundException) if unknown or non-published
  }
}

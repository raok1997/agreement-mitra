package in.agreementmitra.documents.api;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for form projection: {@code GET /api/templates/form?state=..&type=..}. Resolves the
 * dimensions, projects the effective template, and returns the {@link FormSchema} as JSON.
 *
 * <p>The body is deterministic, non-PII system metadata, so the response is <b>cacheable per
 * version</b> rather than {@code no-store}: a strong {@code ETag} equal to the effective template's
 * {@code contentHash} plus a short {@code Cache-Control: public} max-age. A conditional {@code GET}
 * with a matching {@code If-None-Match} returns {@code 304}. Unknown dimensions return {@code 404}
 * via the app-wide {@code ResourceNotFoundException} contract (the response echoes neither {@code
 * state} nor {@code type}). This is the {@code documents} module's first public HTTP endpoint.
 */
@RestController
class TemplateFormController {

  // Small max-age: the schema is stable per template version and the ETag self-invalidates on any
  // contributing-layer change, so a short public cache is safe and cheap.
  private static final Duration MAX_AGE = Duration.ofMinutes(5);

  private final TemplateFormApi templateForm;

  TemplateFormController(TemplateFormApi templateForm) {
    this.templateForm = templateForm;
  }

  @GetMapping("/api/templates/form")
  ResponseEntity<FormSchema> form(
      @RequestParam String state,
      @RequestParam String type,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    FormSchema schema =
        templateForm.formFor(state, type); // 404 (ResourceNotFoundException) if none
    String etag = "\"" + schema.contentHash() + "\"";
    CacheControl cacheControl = CacheControl.maxAge(MAX_AGE).cachePublic();

    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(etag)
          .cacheControl(cacheControl)
          .build();
    }
    return ResponseEntity.ok().eTag(etag).cacheControl(cacheControl).body(schema);
  }
}

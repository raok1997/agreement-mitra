package in.agreementmitra.documents.template;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.DocumentFooterProperties;
import in.agreementmitra.documents.HtmlPdfRenderer;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Package-private implementation of the public {@link DocumentProjectionApi}. Resolves {@code
 * (state, type)} to an {@link EffectiveTemplate} via the {@link TemplateResolver}, validates the
 * submitted data with {@link SubmittedDataValidator} (preview = tolerant, generate = full),
 * compiles it once with the {@link TemplateCompiler}, and either returns that HTML or hands the
 * <b>identical</b> HTML to the public {@link HtmlPdfRenderer} seam.
 *
 * <p><b>Parity by construction:</b> {@link #previewHtml} and {@link #previewPdf} share the one
 * {@link #compile} path, so the live-pane HTML is byte-for-byte the PDF's HTML source (the named
 * non-negotiable). Kept in {@code documents.template} (same package as the records, resolver, and
 * compiler) so no definition-record visibility is widened; Spring wires it as the {@code
 * DocumentProjectionApi} bean the controller and the signing module consume. Constructor injection;
 * no internal/entity type crosses to the caller.
 */
@Service
class DocumentProjectionService implements DocumentProjectionApi {

  /**
   * Default dimensions when a request omits them: the reference base layer's own {@code (state,
   * type)}. The exact default token and how an agreement later carries chosen dimensions are the
   * catalog CR's concern (design D-D).
   */
  private static final Dimensions DEFAULT_DIMENSIONS = new Dimensions("IN", "residential");

  /**
   * The provenance-line reference used when a request carries no {@code documentReference} -- the
   * <b>stateless</b> preview before an agreement is saved (no id exists yet, so no tracking number
   * can). It marks the document as a pre-save draft in both the preview and the PDF. Once the
   * agreement is saved, the caller passes the real tracking number as {@code documentReference}
   * (generate) or the client passes it into the preview request, and the real number replaces this
   * marker. Because the provenance line is <b>body content</b> shared by both faces, preview and
   * PDF stay byte-for-byte in parity.
   */
  static final String PREVIEW_FOOTER_LABEL = "PREVIEW - NOT FOR EXECUTION";

  private final TemplateResolver resolver;
  private final TemplateCompiler compiler;
  private final HtmlPdfRenderer htmlPdfRenderer;

  /**
   * The platform URL shown in the compiled document's provenance line (design D5), resolved once
   * from configuration and passed into the pure compiler. Blank means "omit the URL part". The
   * {@code documents} module holds no brand literal -- the value is app configuration.
   */
  private final String platformUrl;

  /**
   * Injected clock used only to resolve the SYSDATE fallback for the execution date (design D3):
   * the app supplies {@code Clock.systemDefaultZone()}; tests inject {@code Clock.fixed(...)} to
   * pin the header deterministically. Reading it happens here at the projection layer, never in the
   * pure {@link TemplateCompiler}.
   */
  private final Clock clock;

  DocumentProjectionService(
      TemplateResolver resolver,
      TemplateCompiler compiler,
      HtmlPdfRenderer htmlPdfRenderer,
      DocumentFooterProperties footerProperties,
      Clock clock) {
    this.resolver = resolver;
    this.compiler = compiler;
    this.htmlPdfRenderer = htmlPdfRenderer;
    this.platformUrl = footerProperties.platformUrl();
    this.clock = clock;
  }

  @Override
  public String previewHtml(DocumentProjectionRequest request) {
    return compile(request, ProjectionMode.PREVIEW);
  }

  @Override
  public byte[] previewPdf(DocumentProjectionRequest request) {
    // Parity: the compiled body is identical to previewHtml (it carries the screen-only provenance
    // line). The PDF renders as print media -- that body line is hidden -- and the renderer stamps
    // the same reference + URL + page number as the per-page footer furniture in the margin.
    return htmlPdfRenderer.toPdf(compile(request, ProjectionMode.PREVIEW), referenceFor(request));
  }

  /**
   * Generate-as-draft. The active set is the one the request carries -- which is <b>none by
   * default</b>: {@code AgreementDocumentService} constructs the request without {@code
   * activeSections} (the additive field defaults to empty), so a generated draft renders mandatory
   * sections plus any explicitly-given active set (today, none). Wiring the <b>persisted
   * agreement's</b> active set into generate is a deferred follow-on (design D3).
   *
   * <p><b>Parity caveat (design D3):</b> because generate's active set is deferred, a preview that
   * added optional sections differs from the signed draft (mandatory-only) until that follow-on
   * records the agreement's active set. This does <b>not</b> relax the byte-for-byte parity
   * <i>within</i> a face -- a generated draft's HTML still equals its own PDF's HTML source.
   */
  @Override
  public DocumentProjectionResult generate(DocumentProjectionRequest request) {
    EffectiveTemplate effective = resolve(request.dimensions());
    // The tracking number (request.documentReference) + platform URL render as the screen-only body
    // provenance line (for the on-screen preview) AND as the per-page PDF footer furniture. Both
    // are
    // system-owned, so the effective-template identity (reproducibility pin) is unaffected.
    String reference = referenceFor(request);
    String html =
        compile(
            effective,
            request.data(),
            request.activeSections(),
            reference,
            ProjectionMode.GENERATE);
    byte[] pdf = htmlPdfRenderer.toPdf(html, reference);
    return new DocumentProjectionResult(pdf, identityOf(effective));
  }

  /**
   * Resolve -> validate -> compile to HTML in one tier. The single compile path (parity). The
   * preview face drives the compiler with the request's {@code activeSections}, so the live pane
   * (and Download-PDF, which shares this path) reflect exactly the optional sections the user
   * added.
   */
  private String compile(DocumentProjectionRequest request, ProjectionMode mode) {
    return compile(
        resolve(request.dimensions()),
        request.data(),
        request.activeSections(),
        referenceFor(request),
        mode);
  }

  private String compile(
      EffectiveTemplate effective,
      Map<String, Object> data,
      List<String> activeSections,
      String reference,
      ProjectionMode mode) {
    Map<String, Object> coerced = SubmittedDataValidator.validateAndCoerce(effective, data, mode);
    // Resolve the execution date once here (design D3) and pass the concrete value into the pure
    // compiler; preview, previewPdf, and generate all funnel through this path, so they share the
    // same resolved date and stay in parity.
    String executionDate = resolveExecutionDate(coerced);
    // The active set gates optional sections in the one compiler both faces share (parity by
    // construction). A null list is tolerated (treated as empty); titles matching no section are
    // ignored by the compiler.
    Set<String> active = activeSections == null ? Set.of() : new HashSet<>(activeSections);
    // The provenance line (reference + platform URL) is compiled body content shared by both faces,
    // so preview and PDF stay byte-for-byte in parity.
    return compiler.compile(effective, coerced, executionDate, active, reference, platformUrl);
  }

  /**
   * The provenance-line reference for a request: the caller-supplied {@code documentReference} (the
   * tracking number on generate, or the number the client passes into a post-save preview) when
   * present and non-blank, else the {@link #PREVIEW_FOOTER_LABEL} marker (a pre-save preview with
   * no id yet).
   */
  private static String referenceFor(DocumentProjectionRequest request) {
    String reference = request.documentReference();
    return reference == null || reference.isBlank() ? PREVIEW_FOOTER_LABEL : reference;
  }

  /**
   * Resolve the execution date to a concrete <b>ISO</b> value: the submitted {@code agreementDate}
   * (a coerced ISO date string) when present and non-blank, else the current system date read from
   * the injected {@link #clock} (SYSDATE) as ISO. The value is bound under the reserved key and the
   * pure compiler formats it for display like any other date field ({@code dd-MMM-yyyy}), so the
   * header execution line and every other rendered date share one format. Reads a clock but writes
   * no state -- statelessness is preserved.
   */
  private String resolveExecutionDate(Map<String, Object> data) {
    Object submitted = data.get(TemplateCompiler.EXECUTION_DATE_KEY);
    if (submitted != null && !String.valueOf(submitted).isBlank()) {
      return String.valueOf(submitted);
    }
    return LocalDate.now(clock).toString();
  }

  private EffectiveTemplate resolve(DocumentDimensions dimensions) {
    Dimensions target =
        dimensions == null
            ? DEFAULT_DIMENSIONS
            : new Dimensions(dimensions.state(), dimensions.type());
    try {
      return resolver.resolve(target);
    } catch (ResolutionException e) {
      // No effective template resolves for these dimensions. Surface as the app-wide 404 contract;
      // GlobalExceptionHandler maps it to an RFC 9457 ProblemDetail whose detail is a fixed
      // constant
      // and never echoes the requested dimensions.
      throw new ResourceNotFoundException("no template for the requested dimensions");
    }
  }

  private static EffectiveTemplateIdentity identityOf(EffectiveTemplate effective) {
    return new EffectiveTemplateIdentity(
        effective.template().meta().id(), effective.contentHash(), effective.provenance());
  }
}

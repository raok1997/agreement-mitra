package in.agreementmitra.documents.api;

/**
 * Public port of the {@code documents} module for <b>document projection</b>: resolve a {@code
 * (state, type)} to its effective template, validate a submitted data map against that template's
 * field schema, compile it to a self-contained HTML document, and either return that HTML or render
 * it to a PDF.
 *
 * <p><b>Single renderer, parity (non-negotiable):</b> every method compiles through the <b>same</b>
 * {@code TemplateCompiler} output, so the HTML a live pane shows is byte-for-byte the HTML a PDF is
 * produced from -- the document a user previews is the document that is signed. The implementation
 * is package-private in {@code in.agreementmitra.documents.template}; callers depend only on this
 * port.
 *
 * <p>Two validation tiers: the {@code preview*} methods validate present values and tolerate
 * missing ones (placeholders, no {@code required} enforcement); {@link #generate} validates fully
 * (every {@code required} field present-or-defaulted). Invalid data is rejected via {@link
 * in.agreementmitra.DocumentDataInvalidException} (RFC 9457) before any document is drawn.
 * Submitted values, the composed HTML, and the PDF bytes are never logged.
 */
public interface DocumentProjectionApi {

  /**
   * Preview tier: resolve, validate (tolerant), and compile {@code request} to a self-contained,
   * HTML-escaped document for the live pane. Missing fields render placeholders.
   *
   * @throws in.agreementmitra.ResourceNotFoundException if the dimensions resolve to no template
   * @throws in.agreementmitra.DocumentDataInvalidException if a present value is invalid
   */
  String previewHtml(DocumentProjectionRequest request);

  /**
   * Preview tier: the same compiled HTML as {@link #previewHtml}, rendered to a PDF through the
   * offline Gotenberg leg. Invoked only for an explicit Download PDF -- never in the keystroke
   * loop.
   *
   * @throws in.agreementmitra.ResourceNotFoundException if the dimensions resolve to no template
   * @throws in.agreementmitra.DocumentDataInvalidException if a present value is invalid
   */
  byte[] previewPdf(DocumentProjectionRequest request);

  /**
   * Generate tier: resolve, validate <b>fully</b> (every {@code required} field
   * present-or-defaulted and valid), compile, and render to a PDF, returning the bytes plus the
   * {@link EffectiveTemplateIdentity} of the template rendered (the anchor CR-3's pin records).
   *
   * @throws in.agreementmitra.ResourceNotFoundException if the dimensions resolve to no template
   * @throws in.agreementmitra.DocumentDataInvalidException if any field is invalid or a required
   *     field is missing
   */
  DocumentProjectionResult generate(DocumentProjectionRequest request);
}

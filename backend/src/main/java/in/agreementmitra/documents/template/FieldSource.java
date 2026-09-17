package in.agreementmitra.documents.template;

/**
 * Who supplies a field's value. Only {@link #SYSTEM} is ever written into a template ({@code
 * source: system}); a field that declares no source is user-sourced, and the model normalizes that
 * to {@code null} so templates without system fields keep their existing content hash.
 *
 * <p>A system-sourced field is never offered for capture, a submitted value for it is discarded,
 * and its value reaches the compiler only through the server-side system-values channel of a
 * generate projection (for example the stamp duty amount of the attached e-stamp certificate).
 */
enum FieldSource {
  SYSTEM;

  /** Parse a YAML/JSON token; the schema enum already restricts it to {@code system}. */
  static FieldSource from(String token) {
    if ("system".equals(token)) {
      return SYSTEM;
    }
    // The schema enum already guards this; defensive only, never reached post-validation.
    throw new IllegalArgumentException("unknown field source");
  }
}

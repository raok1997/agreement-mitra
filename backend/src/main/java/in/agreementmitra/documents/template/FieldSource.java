package in.agreementmitra.documents.template;

/**
 * Who supplies a field's value. A field that declares no source is user-sourced, and the model
 * normalizes that to {@code null} so templates without a declared source keep their existing
 * content hash. There are three provenances:
 *
 * <ul>
 *   <li><b>user-sourced</b> ({@code source} absent) -- the customer fills it in; it is offered for
 *       capture and a submitted value is honoured.
 *   <li>{@link #SYSTEM} ({@code source: system}) -- "the server supplies it, never show it". It is
 *       never offered for capture, a submitted value for it is discarded, and its value reaches the
 *       compiler only through the server-side system-values channel of a generate projection (for
 *       example the stamp duty amount of the attached e-stamp certificate).
 *   <li>{@link #DERIVED} ({@code source: derived}) -- "the server computes it, show it but do not
 *       ask for it". It stays in the FormSchema so the capture surface can display the computed
 *       value read-only, but a submitted value for it is discarded and the server recomputes it
 *       from other captured values (for example the tenancy term in months, computed from the start
 *       and end dates).
 * </ul>
 *
 * <p>The difference between the two server-supplied cases is only whether the field is shown: a
 * system-sourced field is dropped from the schema entirely, a derived one is projected read-only.
 */
enum FieldSource {
  SYSTEM,
  DERIVED;

  /** Parse a YAML/JSON token; the schema enum already restricts it to the declared values. */
  static FieldSource from(String token) {
    if ("system".equals(token)) {
      return SYSTEM;
    }
    if ("derived".equals(token)) {
      return DERIVED;
    }
    // The schema enum already guards this; defensive only, never reached post-validation.
    throw new IllegalArgumentException("unknown field source");
  }
}

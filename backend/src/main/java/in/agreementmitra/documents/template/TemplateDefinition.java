package in.agreementmitra.documents.template;

import java.util.List;

/**
 * An immutable, fully-validated template definition. Constructed only by {@link
 * TemplateDefinitionLoader} once structural + semantic validation have passed; all list components
 * are defensively copied so the value is unmodifiable.
 *
 * <p>{@code contentHash} is the SHA-256 over the deterministic canonical JSON of this definition's
 * content (see {@link CanonicalJson}); it is <b>not</b> part of the hashed content itself. A
 * definition's identity is the triple {@code (id, version, contentHash)} exposed by {@link
 * #identity()} -- the anchor a future agreement pins to, and the value a future registry uses to
 * detect "same content, different file".
 */
record TemplateDefinition(
    Meta meta,
    List<Field> fields,
    List<Clause> clauses,
    List<Section> sections,
    String contentHash) {

  TemplateDefinition {
    fields = List.copyOf(fields);
    clauses = List.copyOf(clauses);
    sections = List.copyOf(sections);
  }

  /** The {@code (id, version, contentHash)} identity triple of this definition. */
  Identity identity() {
    return new Identity(meta.id(), meta.version(), contentHash);
  }

  /** Version-and-integrity identity of a definition. */
  record Identity(String id, int version, String contentHash) {}
}

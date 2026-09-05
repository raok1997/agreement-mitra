package in.agreementmitra.documents.api;

import java.util.List;

/**
 * The <b>form-projection contract</b>: an immutable, JSON-serializable projection of one resolved
 * effective template into the sections and fields a capture form renders. Produced by the
 * (package-private) form projector and returned by {@link TemplateFormApi}; serialized to JSON by
 * the Spring-Boot-managed Jackson.
 *
 * <p>It is <b>system-owned schema metadata</b> and carries <b>no user data</b> -- only the shape of
 * a form: {@code dimensions} (the {@code state}/{@code type} it was resolved for), the source
 * template's {@code templateId} / {@code version}, its {@code contentHash} (the cache key / HTTP
 * {@code ETag}), and the ordered {@link FormSection}s. Determinism per {@code (state, type,
 * layer-version-set)} is what makes it cacheable per version.
 */
public record FormSchema(
    Dimensions dimensions,
    String templateId,
    int version,
    String contentHash,
    List<FormSection> sections) {

  public FormSchema {
    sections = List.copyOf(sections);
  }

  /** The {@code (state, type)} pair this schema was projected for. */
  public record Dimensions(String state, String type) {}
}

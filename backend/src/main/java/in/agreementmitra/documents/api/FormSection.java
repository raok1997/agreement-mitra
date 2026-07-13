package in.agreementmitra.documents.api;

import java.util.List;

/**
 * One ordered group of form inputs within a {@link FormSchema}. {@code title} is the section
 * heading; {@code fields} are the section's field-key entries as {@link FormField}s, in authored
 * order. Clause-id entries of the source section are document body content, not form inputs, and
 * are not present here.
 *
 * <p>{@code optional} marks a Mandatory ({@code false}) vs Optional ({@code true}) capture section,
 * so the client can show mandatory sections up front and put optional ones into an add-optional
 * catalog. {@code renderKind} is the section's declared document render token ({@code parties |
 * keyvalue | clauses | annexure}), carried as an opaque string for the client to group / label.
 * Both are projected verbatim from the effective template's section (system-owned metadata, never
 * user data).
 */
public record FormSection(
    String title, List<FormField> fields, boolean optional, String renderKind) {

  public FormSection {
    fields = List.copyOf(fields);
  }
}

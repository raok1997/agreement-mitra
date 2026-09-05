package in.agreementmitra.documents.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request to project a document: the {@code (state, type)} {@link DocumentDimensions} to resolve
 * (optional -- a default is used when {@code null}), the working-set {@code data} map keyed by the
 * effective template's declared field keys, and the {@code activeSections} the user has added.
 *
 * <p>{@code data} is a plain field-key -&gt; value map (never a signing/domain type), so the {@code
 * documents} module stays domain-agnostic. It may be partial: in preview projection an absent field
 * renders a placeholder; in generate projection every {@code required} field must be present or
 * carry a declared default. The map holds submitted party PII (names, rent, dates); the projection
 * escapes it at compile time and never logs it.
 *
 * <p>{@code activeSections} is the list of <b>optional section titles the user has added</b>. It is
 * system-owned template structure (section titles), never party data, and is not logged. An
 * omitted, {@code null}, or empty list means "no optional sections added": the compiler then
 * renders only the mandatory sections. A title that matches no declared optional section is ignored
 * (it activates nothing and is never an error). {@code activeSections} can only toggle a
 * template-declared optional section on; it can never introduce a section, field, clause, or markup
 * the template did not declare.
 *
 * <p>{@code documentReference} is an optional, system-generated, <b>non-PII</b> reference (e.g. the
 * agreement id / a short ref) stamped as document furniture by the render layer -- a header/footer
 * page indicator alongside "page X of Y" (design D4). It ties the rendered artifact to the eSign
 * audit trail. It is NOT part of the compiled body HTML (so the effective-template pin is
 * unaffected), carries no Aadhaar / OTP / VID / secret, and is not logged. {@code null}/blank means
 * "no reference furniture" (the keystroke live-preview path, which has no agreement id yet).
 */
public record DocumentProjectionRequest(
    DocumentDimensions dimensions,
    Map<String, Object> data,
    List<String> activeSections,
    String documentReference) {

  public DocumentProjectionRequest {
    // Defensive, unmodifiable copy that tolerates null values (a submitted JSON field may be null);
    // Map.copyOf would reject those.
    data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    // Same discipline for activeSections: an order-preserving, unmodifiable copy that tolerates a
    // null (omitted JSON field), collapsing null to an empty list.
    activeSections =
        activeSections == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(activeSections));
    // Blank collapses to null: "no reference furniture" is a single canonical value.
    documentReference =
        documentReference == null || documentReference.isBlank() ? null : documentReference;
  }

  /**
   * Convenience constructor for callers that add optional sections but no document reference (the
   * keystroke preview path): {@code documentReference} defaults to none.
   */
  public DocumentProjectionRequest(
      DocumentDimensions dimensions, Map<String, Object> data, List<String> activeSections) {
    this(dimensions, data, activeSections, null);
  }

  /**
   * Convenience constructor for callers that add no optional sections (e.g. generate-as-draft from
   * the signing module): {@code activeSections} defaults to empty. Behaviourally identical to the
   * pre-{@code activeSections} request for a document that adds no optional sections.
   */
  public DocumentProjectionRequest(DocumentDimensions dimensions, Map<String, Object> data) {
    this(dimensions, data, null, null);
  }
}

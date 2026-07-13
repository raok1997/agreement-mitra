package in.agreementmitra.documents.template;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared, stateless binding of a JSON tree into the immutable definition model records ({@link
 * Field}, {@link Clause}, {@link Section}, {@link FieldValidation}). Extracted so both the
 * definition loader and the layer-patch loader bind identical shapes the same way -- a patch's
 * {@code addField}/{@code addClause}/{@code addSection}/{@code replaceClause}/{@code
 * replaceSection} payloads are exactly definition fields/clauses/sections, so they must bind
 * byte-identically or the effective template's hash would diverge from a definition's.
 *
 * <p>Binding runs only <b>after</b> the caller's JSON-Schema structural validation has passed, so
 * the enum tokens ({@code type}) are already guaranteed valid; the defensive re-parse here is
 * unreachable in practice and raises a neutral {@link IllegalStateException} rather than a domain
 * error.
 */
final class TemplateNodeBinder {

  /** Matches a {@code {{slotKey}}} placeholder; captures the (declared-field) key. */
  private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

  private TemplateNodeBinder() {}

  static Field bindField(JsonNode field) {
    String key = text(field, "key");
    FieldType type;
    try {
      type = FieldType.from(field.get("type").asText());
    } catch (IllegalArgumentException e) {
      // The schema enum already guards this; defensive only, never reached post-validation.
      throw new IllegalStateException("field '" + key + "': unknown type");
    }
    boolean required = field.path("required").asBoolean(false);
    Object defaultValue = field.has("default") ? scalar(field.get("default")) : null;

    List<String> options = null;
    if (field.has("options")) {
      options = new ArrayList<>();
      for (JsonNode option : field.get("options")) {
        options.add(option.asText());
      }
    }

    FieldValidation validation =
        field.has("validation") ? bindValidation(field.get("validation")) : null;
    String group = field.hasNonNull("group") ? field.get("group").asText() : null;

    return new Field(
        key, text(field, "label"), type, required, defaultValue, options, validation, group);
  }

  static FieldValidation bindValidation(JsonNode validation) {
    Long min = validation.has("min") ? validation.get("min").asLong() : null;
    Long max = validation.has("max") ? validation.get("max").asLong() : null;
    Integer minLength = validation.has("minLength") ? validation.get("minLength").asInt() : null;
    Integer maxLength = validation.has("maxLength") ? validation.get("maxLength").asInt() : null;
    String pattern = validation.hasNonNull("pattern") ? validation.get("pattern").asText() : null;
    return new FieldValidation(min, max, minLength, maxLength, pattern);
  }

  static Clause bindClause(JsonNode clause) {
    if (clause.has("ref")) {
      return new Clause.Ref(clause.get("ref").asText());
    }
    String text = text(clause, "text");
    String showWhen = clause.hasNonNull("showWhen") ? clause.get("showWhen").asText() : null;
    return new Clause.Inline(text(clause, "id"), text, showWhen, parseSlots(text));
  }

  static Section bindSection(JsonNode section) {
    List<String> entries = new ArrayList<>();
    for (JsonNode entry : section.get("entries")) {
      entries.add(entry.asText());
    }
    boolean optional = section.path("optional").asBoolean(false);
    RenderKind render;
    try {
      render =
          section.hasNonNull("render")
              ? RenderKind.from(section.get("render").asText())
              : RenderKind.KEYVALUE;
    } catch (IllegalArgumentException e) {
      // The schema enum already guards this; defensive only, never reached post-validation.
      throw new IllegalStateException(
          "section '" + text(section, "title") + "': unknown render kind");
    }
    return new Section(text(section, "title"), entries, optional, render);
  }

  /** Bind an optional {@code meta.document} header block, or {@code null} when absent. */
  static DocumentMeta bindDocumentMeta(JsonNode meta) {
    if (!meta.hasNonNull("document")) {
      return null;
    }
    JsonNode document = meta.get("document");
    String subtitle = document.hasNonNull("subtitle") ? document.get("subtitle").asText() : null;
    String executionLine =
        document.hasNonNull("executionLine") ? document.get("executionLine").asText() : null;
    return new DocumentMeta(text(document, "title"), subtitle, executionLine);
  }

  /** Parse a plain-text clause's {@code {{slots}}} out, deduplicated, first-appearance order. */
  static List<String> parseSlots(String text) {
    List<String> slots = new ArrayList<>();
    Matcher matcher = SLOT.matcher(text);
    while (matcher.find()) {
      String slot = matcher.group(1);
      if (!slots.contains(slot)) {
        slots.add(slot);
      }
    }
    return slots;
  }

  /** A JSON scalar as its natural Java type, so default type-consistency can be checked. */
  static Object scalar(JsonNode node) {
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    return node.isNull() ? null : node.asText();
  }

  static String text(JsonNode node, String property) {
    return node.get(property).asText();
  }
}

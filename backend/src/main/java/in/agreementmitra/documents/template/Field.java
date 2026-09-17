package in.agreementmitra.documents.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A single typed variable in a definition's data contract. {@code key} is unique within a
 * definition; {@code type} is drawn from the closed {@link FieldType} set. {@code defaultValue}
 * (nullable) holds a type-consistent literal ({@code Boolean}/{@code Long}/{@code
 * java.math.BigDecimal}/{@code String} per the field type); {@code options} is non-null only for
 * {@code enum}; {@code validation} and {@code group} are optional metadata.
 *
 * <p>{@code source} is {@link FieldSource#SYSTEM} for a system-sourced field and {@code null} for
 * an ordinary user-sourced one. {@code placeholder} (nullable) is the text a blank value renders
 * as, inside the usual brackets, instead of the label -- for example a system-sourced amount reads
 * {@code [ Provision for stamp duty ]} in a draft until the server supplies it. Both are omitted
 * from the canonical JSON when {@code null}, so adding them left the content hash of every template
 * that does not use them unchanged.
 */
record Field(
    String key,
    String label,
    FieldType type,
    boolean required,
    Object defaultValue,
    List<String> options,
    FieldValidation validation,
    String group,
    @JsonInclude(JsonInclude.Include.NON_NULL) FieldSource source,
    @JsonInclude(JsonInclude.Include.NON_NULL) String placeholder) {

  Field {
    options = options == null ? null : List.copyOf(options);
  }

  /** A user-sourced field (no {@code source} declared). */
  Field(
      String key,
      String label,
      FieldType type,
      boolean required,
      Object defaultValue,
      List<String> options,
      FieldValidation validation,
      String group) {
    this(key, label, type, required, defaultValue, options, validation, group, null, null);
  }

  /** True when the server, never the submitted data, supplies this field's value. */
  boolean systemSourced() {
    return source == FieldSource.SYSTEM;
  }
}

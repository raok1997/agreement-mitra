package in.agreementmitra.documents.template;

import java.util.List;

/**
 * A single typed variable in a definition's data contract. {@code key} is unique within a
 * definition; {@code type} is drawn from the closed {@link FieldType} set. {@code defaultValue}
 * (nullable) holds a type-consistent literal ({@code Boolean}/{@code Long}/{@code
 * java.math.BigDecimal}/{@code String} per the field type); {@code options} is non-null only for
 * {@code enum}; {@code validation} and {@code group} are optional metadata.
 */
record Field(
    String key,
    String label,
    FieldType type,
    boolean required,
    Object defaultValue,
    List<String> options,
    FieldValidation validation,
    String group) {

  Field {
    options = options == null ? null : List.copyOf(options);
  }
}

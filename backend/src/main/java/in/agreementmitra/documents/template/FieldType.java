package in.agreementmitra.documents.template;

import java.util.Locale;

/**
 * The closed set of field types a definition may declare. Closed so every downstream projector
 * (form widget, validation, renderer formatting) can switch exhaustively over it; additive later.
 */
enum FieldType {
  TEXT,
  LONGTEXT,
  INT,
  MONEY,
  DATE,
  BOOL,
  ENUM;

  /**
   * Parse a definition token (e.g. {@code "money"}) to a {@link FieldType}, case-insensitively.
   *
   * @throws IllegalArgumentException if the token names no known type
   */
  static FieldType from(String token) {
    if (token == null) {
      throw new IllegalArgumentException("field type token is null");
    }
    return valueOf(token.trim().toUpperCase(Locale.ROOT));
  }
}

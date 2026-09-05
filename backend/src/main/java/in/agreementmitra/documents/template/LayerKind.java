package in.agreementmitra.documents.template;

import java.util.Locale;

/**
 * The kind of a layer in the fixed resolution precedence {@code base -> type -> state ->
 * state_type}. {@link #LANGUAGE} is <b>reserved but not applied</b> in this capability (English
 * only). A {@link #BASE} layer is a full {@link TemplateDefinition}; every other kind is a {@link
 * LayerPatch}.
 */
enum LayerKind {
  BASE,
  TYPE,
  STATE,
  STATE_TYPE,
  LANGUAGE;

  /**
   * Parse a patch token (e.g. {@code "state_type"}) to a {@link LayerKind}, case-insensitively.
   *
   * @throws IllegalArgumentException if the token names no known kind
   */
  static LayerKind from(String token) {
    if (token == null) {
      throw new IllegalArgumentException("layer kind token is null");
    }
    return valueOf(token.trim().toUpperCase(Locale.ROOT));
  }
}

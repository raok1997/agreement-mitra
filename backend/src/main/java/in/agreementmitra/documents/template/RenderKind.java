package in.agreementmitra.documents.template;

import java.util.Locale;

/**
 * The closed set of layout kinds a section may declare, telling the compiler how to lay the section
 * out: {@code PARTIES} a party card, {@code KEYVALUE} a label/value table, {@code CLAUSES} a
 * numbered clause list, {@code ANNEXURE} a bulleted annexure, {@code SIGNATURES} the witness /
 * signature block (system-owned markup; a template declares the section so it can be positioned and
 * marked optional per template, rather than the compiler always appending it). Closed so the
 * compiler can switch exhaustively over it; additive later. Default is {@code KEYVALUE} when a
 * section omits {@code render}.
 */
enum RenderKind {
  PARTIES,
  KEYVALUE,
  CLAUSES,
  ANNEXURE,
  SIGNATURES;

  /**
   * Parse a definition token (e.g. {@code "keyvalue"}) to a {@link RenderKind}, case-insensitively.
   *
   * @throws IllegalArgumentException if the token names no known kind
   */
  static RenderKind from(String token) {
    if (token == null) {
      throw new IllegalArgumentException("render kind token is null");
    }
    return valueOf(token.trim().toUpperCase(Locale.ROOT));
  }
}

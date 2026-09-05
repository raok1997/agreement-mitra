package in.agreementmitra.documents.template;

import java.util.Locale;

/** Lifecycle status a definition declares in {@code meta.status}. */
enum TemplateStatus {
  DRAFT,
  LEGAL_APPROVED,
  PUBLISHED,
  DEPRECATED;

  /**
   * Parse a definition token (e.g. {@code "legal_approved"}) to a {@link TemplateStatus},
   * case-insensitively.
   *
   * @throws IllegalArgumentException if the token names no known status
   */
  static TemplateStatus from(String token) {
    if (token == null) {
      throw new IllegalArgumentException("status token is null");
    }
    return valueOf(token.trim().toUpperCase(Locale.ROOT));
  }
}

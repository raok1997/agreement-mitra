package in.agreementmitra.documents.template;

import java.util.Locale;
import java.util.Map;

/**
 * Derives a human display label from an enum option value -- the single source shared by the form
 * projection (the list-box label) and the compiler (the value rendered in the preview / PDF body),
 * so both read identically. Presentation only: the stored/submitted value is never changed, and
 * {@code showWhen} still evaluates on the raw value.
 *
 * <p>Rule: replace {@code _}/whitespace with a space and Title-Case each word, keep a defined
 * acronym set upper-case ({@code upi -> UPI}), and preserve a token that already contains an
 * upper-case letter ({@code 1BHK}) verbatim. Pure and deterministic, so the projected form schema
 * stays cacheable and a pinned re-render stays byte-stable.
 */
final class OptionLabels {

  /** Known acronyms rendered upper-case in a label (extend as new enums add them). */
  private static final Map<String, String> ACRONYMS = Map.of("upi", "UPI", "pg", "PG");

  private OptionLabels() {}

  /** The human label for an enum option value; a null/blank value is returned unchanged. */
  static String humanize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String[] words = value.strip().split("[_\\s]+");
    StringBuilder out = new StringBuilder(value.length() + 4);
    for (int i = 0; i < words.length; i++) {
      if (i > 0) {
        out.append(' ');
      }
      out.append(humanizeWord(words[i]));
    }
    return out.toString();
  }

  private static String humanizeWord(String word) {
    if (word.isEmpty()) {
      return word;
    }
    String acronym = ACRONYMS.get(word.toLowerCase(Locale.ROOT));
    if (acronym != null) {
      return acronym;
    }
    // Preserve a token that already carries an upper-case letter (e.g. 1BHK / 2BHK) verbatim.
    if (word.chars().anyMatch(Character::isUpperCase)) {
      return word;
    }
    return Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }
}

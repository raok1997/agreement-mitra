package in.agreementmitra;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log-safe rendering of agreement ids (agreement-id-debug-logging D1-D3).
 *
 * <p>The agreement id is a bearer capability: holding the UUID grants anonymous read, draft upload,
 * finalise, contact edit and payment. Log lines, constructed exception messages and {@code
 * toString()} output therefore carry only its first 8 hex characters plus an ellipsis ({@code
 * 1a2b3c4d…}) -- 32 of the 122 random bits, leaving 2^90 candidates. An operator finds the row with
 * {@code WHERE id::text LIKE '1a2b3c4d%'}.
 */
public final class AgreementIds {

  private static final int PREFIX_LENGTH = 8;
  private static final String ELLIPSIS = "…";
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

  private AgreementIds() {}

  /**
   * The redacted form of {@code agreementId}; {@code "null"} for null so a log call never throws.
   */
  public static String redact(UUID agreementId) {
    if (agreementId == null) {
      return "null";
    }
    return agreementId.toString().substring(0, PREFIX_LENGTH) + ELLIPSIS;
  }

  /**
   * {@code text} with every canonical UUID replaced by its redacted form, e.g. {@code
   * drafts/<uuid>.pdf} becomes {@code drafts/1a2b3c4d….pdf}. Null passes through.
   */
  public static String redactIn(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = UUID_PATTERN.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    while (matcher.find()) {
      String prefix = matcher.group().substring(0, PREFIX_LENGTH).toLowerCase(Locale.ROOT);
      matcher.appendReplacement(out, Matcher.quoteReplacement(prefix + ELLIPSIS));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}

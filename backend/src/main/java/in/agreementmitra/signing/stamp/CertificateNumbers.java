package in.agreementmitra.signing.stamp;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalisation and redaction for the SHCIL certificate number.
 *
 * <p><b>Normalisation matters for correctness.</b> The certificate number is the single-use token
 * that proves duty was paid, and its uniqueness is enforced by a database index over the
 * <em>normalised</em> value. If the application stored what staff typed verbatim, {@code
 * "in-ka123456 "} and {@code "IN-KA123456"} would be two rows and one purchased certificate would
 * be spent twice - a legal defect invisible until challenged. Normalising here (trim + collapse
 * internal whitespace + uppercase) makes the two collide exactly as they should.
 *
 * <p><b>Redaction matters for hygiene.</b> Because the number evidences duty payment, it is treated
 * as sensitive: logs carry {@link #redact(String)} output (last four characters) and never the
 * whole value.
 */
public final class CertificateNumbers {

  /**
   * Accepted shape after normalisation: 6-64 characters of uppercase alphanumerics, hyphen, slash,
   * or single spaces. Deliberately permissive about the SHCIL format itself (which varies by state)
   * but strict about the character set, so the value is safe to draw into a PDF with a Standard-14
   * font and can never smuggle a control character into a log line.
   */
  private static final Pattern WELL_FORMED = Pattern.compile("[A-Z0-9][A-Z0-9 /-]{4,62}[A-Z0-9]");

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private static final int VISIBLE_SUFFIX = 4;

  private CertificateNumbers() {}

  /**
   * Canonical storage form: trimmed, internal whitespace collapsed, uppercased. Null in, null out.
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    return WHITESPACE_RUN.matcher(raw.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);
  }

  /** True if an already-{@link #normalize(String) normalised} value has an acceptable shape. */
  public static boolean isWellFormed(String normalized) {
    return normalized != null && WELL_FORMED.matcher(normalized).matches();
  }

  /**
   * Log-safe rendering: the last four characters only, prefixed with {@code ***}. A null or short
   * value degrades to a fully masked token rather than leaking what little there is.
   */
  public static String redact(String value) {
    if (value == null || value.isBlank()) {
      return "***";
    }
    String trimmed = value.trim();
    if (trimmed.length() <= VISIBLE_SUFFIX) {
      return "***";
    }
    return "***" + trimmed.substring(trimmed.length() - VISIBLE_SUFFIX);
  }
}

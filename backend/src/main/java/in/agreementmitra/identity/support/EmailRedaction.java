package in.agreementmitra.identity.support;

/**
 * Shared email-redaction helper for log lines within the identity module. Masks the local part so a
 * leaked log never carries a full address: {@code alice@gmail.com -> a***@gmail.com}. Public so the
 * module's internal sub-packages ({@code oauth}, {@code session}, {@code api}) can share one
 * implementation; it lives in an internal package, so no other Modulith module sees it.
 *
 * <p>This is a logging veneer only -- the full email is still stored (to display the identity) and
 * returned to the authenticated owner via {@code /api/auth/me}. It exists so no code path logs an
 * unredacted address.
 */
public final class EmailRedaction {

  private EmailRedaction() {}

  /**
   * Redact an email for a log line: keep the first local-part character and the domain, mask the
   * rest. Null/blank/malformed input collapses to {@code "***"} so a log line never leaks and never
   * throws. Never returns the raw address.
   */
  public static String redact(String email) {
    if (email == null || email.isBlank()) {
      return "***";
    }
    int at = email.indexOf('@');
    if (at <= 0 || at == email.length() - 1) {
      // No usable local part or no domain -- do not risk echoing it.
      return "***";
    }
    String domain = email.substring(at);
    return email.charAt(0) + "***" + domain;
  }
}

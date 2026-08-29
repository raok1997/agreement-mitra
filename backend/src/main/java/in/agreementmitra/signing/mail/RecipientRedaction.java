package in.agreementmitra.signing.mail;

/**
 * Email redaction for log lines in the signing module's delivery path: masks the local part so a
 * leaked log never carries a full address ({@code asha@example.com -> a***@example.com}).
 *
 * <p>Deliberately a second, tiny copy of the same rule the identity module keeps for its own log
 * lines. That helper lives in {@code identity}'s internal {@code support} package and is invisible
 * to this module by design; importing it would breach the Modulith boundary, and widening {@code
 * identity}'s public surface to share nine lines of string masking would be the worse trade.
 *
 * <p>This is a <b>logging veneer only</b>. The full address is still stored on the delivery record
 * (a delivery record has to say where the document actually went) and is still handed to the mail
 * provider. Its purpose is that no code path logs an unredacted address.
 */
public final class RecipientRedaction {

  private RecipientRedaction() {}

  /**
   * Keep the first local-part character and the domain, mask the rest. Null, blank, or malformed
   * input collapses to {@code "***"}, so a log line never leaks and never throws. Never returns the
   * raw address.
   */
  public static String redact(String email) {
    if (email == null || email.isBlank()) {
      return "***";
    }
    int at = email.indexOf('@');
    if (at <= 0 || at == email.length() - 1) {
      // No usable local part or no domain - do not risk echoing it.
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}

package in.agreementmitra.identity.oauth;

/**
 * A Google login could not be completed: an unknown/reused {@code state}, a failed token exchange,
 * or an ID token that failed validation (bad signature/issuer/audience, expired, or {@code
 * email_verified != true}). Thrown before any identity, handoff, or session is materialized.
 *
 * <p>The message is for redacted server-side logging only -- it MUST NOT echo a token, the
 * authorization code, an email, or the {@code state}, and it MUST NOT reach the client body. The
 * handshake returns an identical failure for every cause, so the client learns nothing (no
 * enumeration oracle, no validation-detail leak).
 *
 * <p>Java-{@code public} so the module's {@code api} controller (a sibling package) can map it to a
 * fixed HTTP response, and the {@code session} exchange can reuse it; it stays in an internal
 * package, so no other Modulith module sees it.
 */
public class InvalidLoginException extends RuntimeException {

  public InvalidLoginException(String message) {
    super(message);
  }

  public InvalidLoginException(String message, Throwable cause) {
    super(message, cause);
  }
}

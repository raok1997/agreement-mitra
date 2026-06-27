package in.agreementmitra;

/**
 * Thrown when an agreement's draft cannot be stamped — typically because the uploaded draft is
 * unparseable (encrypted, corrupt, zero-page) or composition otherwise fails. Mapped by {@link
 * GlobalExceptionHandler} to a 422 ProblemDetail. Lives in the root package (not a Spring Modulith
 * module) so the cross-cutting handler can reference it without reaching into a module's internals.
 *
 * <p>The message is for redacted server-side logging only and MUST NOT reach the response body (the
 * handler renders a fixed constant detail — never the message, the filename, or any draft content).
 */
public class StampFailedException extends RuntimeException {

  public StampFailedException(String message) {
    super(message);
  }

  public StampFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}

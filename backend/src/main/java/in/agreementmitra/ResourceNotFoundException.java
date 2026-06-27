package in.agreementmitra;

/**
 * Thrown when a requested resource does not exist; mapped by {@link GlobalExceptionHandler} to a
 * 404 ProblemDetail. Lives in the root package (not a Spring Modulith module) so the cross-cutting
 * handler can reference it without reaching into any module's internals.
 *
 * <p>The message is for redacted server-side logging only — it MUST NOT reach the client body. The
 * handler renders a constant {@code detail}, never this message or the requested id, so a future
 * lookup keyed on PII (email/phone) cannot leak it.
 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}

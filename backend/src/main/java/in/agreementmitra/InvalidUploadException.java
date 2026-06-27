package in.agreementmitra;

/**
 * Thrown when an uploaded file fails content/shape validation (not a PDF, empty, or too many
 * parts); mapped by {@link GlobalExceptionHandler} to a 400 ProblemDetail. Lives in the root
 * package (not a Spring Modulith module) so the cross-cutting handler can reference it without
 * reaching into any module's internals.
 *
 * <p>The handler renders a constant {@code detail} — never this message and never the uploaded
 * filename/content (both attacker-controlled). The message is for redacted server-side logging
 * only.
 */
public class InvalidUploadException extends RuntimeException {

  public InvalidUploadException(String message) {
    super(message);
  }
}

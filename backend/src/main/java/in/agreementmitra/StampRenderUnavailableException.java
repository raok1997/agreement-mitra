package in.agreementmitra;

/**
 * Stamp intake could not re-render the agreement's instrument because the document renderer is
 * unavailable. Retryable: it is raised before any blob is written or state changes, so the signing
 * request stays awaiting a stamp and the certificate stays unused. Deliberately NOT a {@link
 * StampFailedException}, which drives the request to the terminal {@code STAMP_FAILED} and abandons
 * the order -- wrong for a transient outage on an order whose certificate has already been bought.
 *
 * <p>Mapped by {@link GlobalExceptionHandler} to a 503 ProblemDetail with a constant detail. The
 * message is for server-side logging only.
 */
public class StampRenderUnavailableException extends RuntimeException {

  public StampRenderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

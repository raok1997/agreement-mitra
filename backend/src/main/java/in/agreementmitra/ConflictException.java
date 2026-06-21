package in.agreementmitra;

/**
 * Thrown when a request conflicts with the resource's current state; mapped by {@link
 * GlobalExceptionHandler} to a 409 ProblemDetail. Lives in the root package (not a Spring Modulith
 * module) so the cross-cutting handler can reference it without reaching into any module's
 * internals.
 *
 * <p>Carries a {@link Kind} rather than client-facing text: the handler renders a fixed per-kind
 * {@code type}/{@code title}/{@code detail} constant, so no client text is ever derived from input.
 * The message is for redacted server-side logging only and MUST NOT reach the body.
 */
public class ConflictException extends RuntimeException {

  /**
   * The specific conflict, so the handler can emit a distinct problem {@code type} URN per case.
   */
  public enum Kind {
    /** A draft upload was attempted after signing was already requested (draft finalized). */
    DRAFT_FROZEN,
    /** Signing was requested for an agreement that has no uploaded draft. */
    DRAFT_REQUIRED
  }

  private final Kind kind;

  private ConflictException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public static ConflictException draftFrozen() {
    return new ConflictException(Kind.DRAFT_FROZEN, "draft frozen: signing already requested");
  }

  public static ConflictException draftRequired() {
    return new ConflictException(Kind.DRAFT_REQUIRED, "draft required before signing");
  }

  public Kind kind() {
    return kind;
  }
}

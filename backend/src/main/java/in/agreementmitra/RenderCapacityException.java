package in.agreementmitra;

/**
 * Thrown by the {@code documents} module when a render is refused for capacity: every render slot
 * it may use is busy and either the waiting room is full or the bounded wait elapsed
 * (anonymous-surface-abuse-controls D6). Distinct from {@code documents.DocumentRenderException} so
 * a capacity refusal -- retryable, nothing was attempted -- is never confused with a render that
 * failed. Carries no rendered content.
 *
 * <p>Lives in the root package, like {@link DocumentDataInvalidException}, so {@link
 * GlobalExceptionHandler} can map it to {@code 503} without the root depending on a module, which
 * {@code ModularityTests} reports as a cycle.
 */
public class RenderCapacityException extends RuntimeException {

  public RenderCapacityException(String message) {
    super(message);
  }
}

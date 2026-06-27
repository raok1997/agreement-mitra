package in.agreementmitra;

/**
 * One entry in a validation error body's {@code errors} list: the offending field and the
 * constraint's resolved message. Deliberately carries NO rejected value — this is identity/legal
 * infra and an error body must never echo submitted input or PII.
 *
 * <p>Named to avoid collision with {@code org.springframework.validation.FieldError}, which {@link
 * GlobalExceptionHandler} also references.
 */
public record FieldErrorDetail(String field, String message) {}

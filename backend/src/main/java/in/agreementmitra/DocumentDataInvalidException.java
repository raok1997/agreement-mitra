package in.agreementmitra;

import java.util.List;

/**
 * Raised when a submitted document-projection data map fails validation against the effective
 * template's field schema (wrong type, out-of-bounds, bad pattern, non-member enum, or a missing
 * required field in generate mode). Carries only field keys + rule tokens -- <b>never</b> a
 * rejected data value -- because this is identity/legal infra and an error body must never echo
 * submitted input or PII.
 *
 * <p>Lives in the root package (the shared kernel), alongside the other app-wide exceptions, so a
 * module can throw it and the root {@link GlobalExceptionHandler} maps it to the RFC 9457 contract
 * without any module-internal type crossing a boundary.
 */
public class DocumentDataInvalidException extends RuntimeException {

  private final transient List<FieldErrorDetail> errors;

  public DocumentDataInvalidException(List<FieldErrorDetail> errors) {
    super("submitted document data failed validation");
    this.errors = List.copyOf(errors);
  }

  /** The field-key + rule-token violations. Never carries a rejected value. */
  public List<FieldErrorDetail> errors() {
    return errors;
  }
}

package in.agreementmitra;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Application-wide RFC 9457 error contract for the JSON API. Lives in the root package — NOT a
 * sub-package — so Spring Modulith does not classify it as a module (direct sub-packages of the
 * root become modules); same precedent as {@code SecurityConfig}. It serves every module and MUST
 * import no module-internal type (root is not a module, so a root→module reach-in would not be
 * caught by {@code ModularityTests}).
 *
 * <p><b>PII safety (non-negotiable — identity/legal infra):</b> no client-facing text is ever
 * derived from an exception message, the rejected value, or the requested id. Every {@code detail}
 * is a fixed per-category constant; {@code errors[]} entries carry only field + constraint message
 * (never {@code FieldError.getRejectedValue()}). {@code MethodArgumentTypeMismatchException} and
 * {@code HttpMessageNotReadableException} messages can embed the offending value/payload — hence
 * the constant detail.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  // Stable, non-null token for class-level / cross-field errors (e.g. @ValidSignerSet), which Bean
  // Validation reports as a global ObjectError not bound to a field. ASSUMPTION: @ValidSignerSet is
  // the only class-level constraint today, so every global error maps here. If a second class-level
  // constraint is added, extend this mapping — the mapping unit test will fail loudly otherwise.
  private static final String SIGNER_SET_FIELD = "signers";

  private static final String TYPE_VALIDATION = "urn:agreementmitra:problem:validation";
  private static final String TYPE_MALFORMED = "urn:agreementmitra:problem:malformed-request";
  private static final String TYPE_MISMATCH = "urn:agreementmitra:problem:type-mismatch";
  private static final String TYPE_CONSTRAINT = "urn:agreementmitra:problem:constraint-violation";
  private static final String TYPE_NOT_FOUND = "urn:agreementmitra:problem:resource-not-found";
  private static final String TYPE_DRAFT_FROZEN = "urn:agreementmitra:problem:draft-frozen";
  private static final String TYPE_DRAFT_REQUIRED = "urn:agreementmitra:problem:draft-required";
  private static final String TYPE_INVALID_UPLOAD = "urn:agreementmitra:problem:invalid-upload";
  private static final String TYPE_PAYLOAD_TOO_LARGE =
      "urn:agreementmitra:problem:payload-too-large";

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail body =
        problem(
            HttpStatus.BAD_REQUEST,
            TYPE_VALIDATION,
            "Validation failed",
            "One or more fields are invalid.");
    body.setProperty("errors", toFieldErrors(ex.getBindingResult()));
    return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // Build from a constant — ex.getMessage() can contain raw-payload fragments.
    ProblemDetail body =
        problem(
            HttpStatus.BAD_REQUEST,
            TYPE_MALFORMED,
            "Malformed request",
            "The request body could not be read.");
    return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    // The real ResponseEntityExceptionHandler hook (covers MethodArgumentTypeMismatchException).
    // Constant detail — ex.getMessage() embeds the offending value (e.g. "not-a-uuid").
    ProblemDetail body =
        problem(
            HttpStatus.BAD_REQUEST,
            TYPE_MISMATCH,
            "Invalid request parameter",
            "A request parameter has an invalid format.");
    return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
    // Constant detail — never ex.getMessage() or the requested id.
    return problem(
        HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Resource not found", "Resource not found");
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
    // Defensive: @Validated method-param violations otherwise map to 500. No endpoint reaches this
    // today (no @Validated params), so it is unit-tested only.
    return problem(
        HttpStatus.BAD_REQUEST,
        TYPE_CONSTRAINT,
        "Constraint violation",
        "A request parameter is invalid.");
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail handleConflict(ConflictException ex) {
    // Distinct type URN per conflict kind so clients can disambiguate; constant detail (never the
    // exception message or any input).
    return switch (ex.kind()) {
      case DRAFT_FROZEN ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_DRAFT_FROZEN,
              "Draft finalized",
              "The draft cannot be changed after signing has been requested.");
      case DRAFT_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_DRAFT_REQUIRED,
              "Draft required",
              "A draft must be uploaded before signing can be requested.");
    };
  }

  @ExceptionHandler(InvalidUploadException.class)
  ProblemDetail handleInvalidUpload(InvalidUploadException ex) {
    // Constant detail — never the exception message or the attacker-controlled filename/content.
    return problem(
        HttpStatus.BAD_REQUEST,
        TYPE_INVALID_UPLOAD,
        "Invalid upload",
        "The upload must be a single PDF file.");
  }

  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // Override the superclass hook (ResponseEntityExceptionHandler already maps this type — a
    // second @ExceptionHandler would be ambiguous). Oversized multipart → 400 (default is 500).
    return handleExceptionInternal(ex, payloadTooLarge(), headers, HttpStatus.BAD_REQUEST, request);
  }

  /** Constant 400 body for an oversized upload. Package-private for direct unit testing. */
  ProblemDetail payloadTooLarge() {
    return problem(
        HttpStatus.BAD_REQUEST,
        TYPE_PAYLOAD_TOO_LARGE,
        "Upload too large",
        "The uploaded file exceeds the maximum allowed size.");
  }

  /**
   * Maps each binding error to a {@link FieldErrorDetail} of field + constraint message only.
   * Field-bound errors use their path; class-level / global errors use the stable {@code "signers"}
   * token. Never reads the rejected value. Package-private for direct unit testing without a Spring
   * context.
   */
  List<FieldErrorDetail> toFieldErrors(BindingResult result) {
    return result.getAllErrors().stream()
        .map(
            error ->
                new FieldErrorDetail(
                    error instanceof FieldError fieldError
                        ? fieldError.getField()
                        : SIGNER_SET_FIELD,
                    error.getDefaultMessage()))
        .toList();
  }

  private static ProblemDetail problem(
      HttpStatus status, String typeUrn, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    URI type = URI.create(typeUrn);
    problem.setType(type);
    problem.setTitle(title);
    // Pin `instance` to a constant. Spring otherwise auto-populates it with the request path, which
    // would reflect the requested id (or a mistyped path value) back into the body — violating the
    // never-echo invariant. Framework population only fills a null instance, so this wins.
    problem.setInstance(type);
    return problem;
  }
}

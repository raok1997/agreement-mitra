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
  private static final String TYPE_CONTACT_REQUIRED = "urn:agreementmitra:problem:contact-required";
  private static final String TYPE_NOT_SIGNABLE = "urn:agreementmitra:problem:not-signable";
  private static final String TYPE_STAMP_REQUIRED = "urn:agreementmitra:problem:stamp-required";
  private static final String TYPE_STAMP_ALREADY_ATTACHED =
      "urn:agreementmitra:problem:stamp-already-attached";
  private static final String TYPE_CERTIFICATE_ALREADY_USED =
      "urn:agreementmitra:problem:certificate-already-used";
  private static final String TYPE_ORDER_NOT_PLACED = "urn:agreementmitra:problem:order-not-placed";
  private static final String TYPE_PAYMENT_REQUIRED = "urn:agreementmitra:problem:payment-required";
  private static final String TYPE_JURISDICTION_UNSUPPORTED =
      "urn:agreementmitra:problem:jurisdiction-unsupported";
  private static final String TYPE_PAYMENT_REFERENCE_ALREADY_USED =
      "urn:agreementmitra:problem:payment-reference-already-used";
  private static final String TYPE_AGREEMENT_CLOSED = "urn:agreementmitra:problem:agreement-closed";
  private static final String TYPE_CONTACTS_FROZEN = "urn:agreementmitra:problem:contacts-frozen";
  private static final String TYPE_INVALID_UPLOAD = "urn:agreementmitra:problem:invalid-upload";
  private static final String TYPE_PAYLOAD_TOO_LARGE =
      "urn:agreementmitra:problem:payload-too-large";
  private static final String TYPE_STAMP_FAILED = "urn:agreementmitra:problem:stamp-failed";
  private static final String TYPE_DOCUMENT_DATA_INVALID =
      "urn:agreementmitra:problem:document-data-invalid";

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
      case CONTACT_REQUIRED -> contactRequiredProblem(ex);
      case JURISDICTION_UNSUPPORTED -> jurisdictionUnsupportedProblem(ex);
      case NOT_SIGNABLE ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_NOT_SIGNABLE,
              "Not signable",
              "The document has no signature anchor, so a signing request cannot be created.");
      case STAMP_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_STAMP_REQUIRED,
              "Stamp required",
              "An e-stamp must be attached before signing can be requested.");
      case STAMP_ALREADY_ATTACHED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_STAMP_ALREADY_ATTACHED,
              "Stamp already attached",
              "This agreement is not awaiting an e-stamp upload.");
      case CERTIFICATE_ALREADY_USED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_CERTIFICATE_ALREADY_USED,
              "Certificate already used",
              "This e-stamp certificate has already been used.");
      case ORDER_NOT_PLACED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_ORDER_NOT_PLACED,
              "Order not placed",
              "This agreement has not been finalised yet.");
      case PAYMENT_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_PAYMENT_REQUIRED,
              "Payment required",
              "This agreement must be paid for before this step can proceed.");
      case PAYMENT_REFERENCE_ALREADY_USED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_PAYMENT_REFERENCE_ALREADY_USED,
              "Payment reference already used",
              "This payment reference has already been recorded.");
      case AGREEMENT_CLOSED ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_AGREEMENT_CLOSED,
              "Agreement closed",
              "This agreement has been closed and cannot be changed.");
      // Its own type, not draft-frozen: a client has to be able to say WHICH thing is locked, and
      // that a paid order's contacts will never accept a retry.
      case CONTACTS_FROZEN ->
          problem(
              HttpStatus.CONFLICT,
              TYPE_CONTACTS_FROZEN,
              "Contacts frozen",
              "Contact details cannot be changed once payment for this agreement is settled.");
    };
  }

  @ExceptionHandler(InvalidUploadException.class)
  ProblemDetail handleInvalidUpload(InvalidUploadException ex) {
    // Constant detail — never the exception message or the attacker-controlled filename/content.
    return problem(
        HttpStatus.BAD_REQUEST,
        TYPE_INVALID_UPLOAD,
        "Invalid upload",
        "The upload must be a single file of an accepted type and size.");
  }

  @ExceptionHandler(StampFailedException.class)
  ProblemDetail handleStampFailed(StampFailedException ex) {
    // 422: the stored draft was accepted at upload but cannot be processed (parsed/stamped) now.
    // Constant detail — never the exception message or any draft content (fail closed, no leak).
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        TYPE_STAMP_FAILED,
        "Stamping failed",
        "The uploaded draft could not be stamped.");
  }

  @ExceptionHandler(DocumentDataInvalidException.class)
  ProblemDetail handleDocumentDataInvalid(DocumentDataInvalidException ex) {
    // Submitted document-projection data failed schema validation (wrong type, out-of-bounds, bad
    // pattern, non-member enum, or a missing required field in generate mode). 400 + errors[], the
    // same shape as bean-validation failures. The exception already carries only field keys + rule
    // tokens (never a rejected value), so errors[] is safe to surface verbatim -- never-echo holds.
    // Passive today: no endpoint raises this yet; CR-2's preview endpoint exercises the HTTP path.
    ProblemDetail body =
        problem(
            HttpStatus.BAD_REQUEST,
            TYPE_DOCUMENT_DATA_INVALID,
            "Validation failed",
            "One or more submitted fields are invalid.");
    body.setProperty("errors", ex.errors());
    return body;
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

  /**
   * A party is not reachable on any enabled delivery channel.
   *
   * <p>The {@code detail} stays a fixed constant, per this class's contract. What the customer
   * needs beyond that - <b>which</b> party to fix - travels as a structured {@code
   * unreachableParties} property instead, carrying role-and-position labels ("tenant 1") only.
   * Those are safe by construction: no name, no address, no number, and nothing the caller does not
   * already hold, since they are acting on this agreement.
   *
   * <p>The wording deliberately says neither "email or mobile" nor "before signing". Reachability
   * is per enabled channel now, so a mobile alone does not qualify while SMS is off; and the check
   * first fires at order creation, well before signing.
   */
  private static ProblemDetail contactRequiredProblem(ConflictException ex) {
    ProblemDetail body =
        problem(
            HttpStatus.CONFLICT,
            TYPE_CONTACT_REQUIRED,
            "Contact required",
            "Every party needs a contact we can reach them on before payment.");
    if (!ex.partyLabels().isEmpty()) {
      body.setProperty("unreachableParties", ex.partyLabels());
    }
    return body;
  }

  /**
   * The agreement's duty jurisdiction is not one we can fulfil. A <b>distinct</b> type from
   * payment-required on purpose: a different person resolves each, so an operator reading a 409
   * must be able to tell which precondition stopped the pipeline.
   *
   * <p>Both extra properties are safe under the never-echo invariant because both are
   * <b>server-derived</b>, not request-derived: the rejected code comes from the agreement's pinned
   * template and the eligible list from configuration. The eligible list is public by construction
   * - the template picker and the published terms both disclose it - and it names only what IS
   * eligible, never what is under consideration.
   *
   * <p>The wording avoids "we do not serve your state": the template remains fully usable to draft
   * and download, and only stamping and eSign are unavailable.
   */
  private static ProblemDetail jurisdictionUnsupportedProblem(ConflictException ex) {
    ProblemDetail body =
        problem(
            HttpStatus.CONFLICT,
            TYPE_JURISDICTION_UNSUPPORTED,
            "Jurisdiction not available for stamping",
            "This agreement can be drafted and downloaded, but stamping and eSign are not yet"
                + " available for its jurisdiction.");
    if (ex.rejectedJurisdiction() != null) {
      body.setProperty("jurisdiction", ex.rejectedJurisdiction());
    }
    if (!ex.eligibleJurisdictions().isEmpty()) {
      body.setProperty("eligibleJurisdictions", ex.eligibleJurisdictions());
    }
    return body;
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

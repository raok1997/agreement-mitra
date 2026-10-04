package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Unit tests for the error-mapping logic — no Spring context, no I/O. Exercises the field/global
 * mapping, the never-echo-PII invariant, and the constant-detail handlers directly.
 */
class GlobalExceptionHandlerTest {

  private static final String OBJECT_NAME = "createAgreementRequest";

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void mapsFieldAndGlobalErrorsToFieldErrorDetails() {
    var binding = new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
    binding.addError(new FieldError(OBJECT_NAME, "propertyAddress", "must not be blank"));
    binding.addError(new ObjectError(OBJECT_NAME, "must have at least one owner and one tenant"));

    List<FieldErrorDetail> errors = handler.toFieldErrors(binding);

    assertThat(errors)
        .containsExactlyInAnyOrder(
            new FieldErrorDetail("propertyAddress", "must not be blank"),
            new FieldErrorDetail("signers", "must have at least one owner and one tenant"));
  }

  @Test
  void crossFieldGlobalErrorGetsStableNonNullFieldToken() {
    var binding = new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
    binding.addError(new ObjectError(OBJECT_NAME, "invalid signer set"));

    List<FieldErrorDetail> errors = handler.toFieldErrors(binding);

    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).field()).isEqualTo("signers").isNotNull();
  }

  @Test
  void neverEchoesRejectedValueOrPii() throws Exception {
    String rejectedEmail = "evil@example.com";
    var binding = new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
    binding.addError(
        new FieldError(
            OBJECT_NAME,
            "email",
            rejectedEmail,
            false,
            null,
            null,
            "must be a well-formed email address"));

    List<FieldErrorDetail> errors = handler.toFieldErrors(binding);

    assertThat(errors)
        .containsExactly(new FieldErrorDetail("email", "must be a well-formed email address"));
    // The rejected value must not survive into the serialized body anywhere.
    assertThat(mapper.writeValueAsString(errors)).doesNotContain(rejectedEmail);
  }

  @Test
  void listsEveryFieldViolation() {
    var binding = new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
    binding.addError(new FieldError(OBJECT_NAME, "propertyAddress", "must not be blank"));
    binding.addError(new FieldError(OBJECT_NAME, "monthlyRent", "must be greater than 0"));

    List<FieldErrorDetail> errors = handler.toFieldErrors(binding);

    assertThat(errors)
        .extracting(FieldErrorDetail::field)
        .containsExactlyInAnyOrder("propertyAddress", "monthlyRent");
  }

  @Test
  void constraintViolationMapsTo400WithConstantDetail() {
    var offendingFragment = "must-match-uuid-format";
    var ex = new ConstraintViolationException("id: " + offendingFragment, Set.of());

    ProblemDetail problem = handler.handleConstraintViolation(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("A request parameter is invalid.");
    assertThat(problem.getDetail()).doesNotContain(offendingFragment);
  }

  @Test
  void resourceNotFoundMapsTo404WithConstantDetailNotReflectingId() {
    var requestedId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    var ex = new ResourceNotFoundException("Agreement not found: " + requestedId);

    ProblemDetail problem = handler.handleResourceNotFound(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getDetail()).isEqualTo("Resource not found");
    assertThat(problem.getDetail()).doesNotContain(requestedId);
  }

  @Test
  void draftFrozenConflictMapsTo409WithItsOwnTypeUrn() {
    ProblemDetail problem = handler.handleConflict(ConflictException.draftFrozen());

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getType().toString()).isEqualTo("urn:agreementmitra:problem:draft-frozen");
    assertThat(problem.getDetail())
        .isEqualTo("The draft cannot be changed after signing has been requested.");
  }

  @Test
  void draftRequiredConflictMapsTo409WithADistinctTypeUrn() {
    ProblemDetail problem = handler.handleConflict(ConflictException.draftRequired());

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:draft-required")
        .isNotEqualTo("urn:agreementmitra:problem:draft-frozen");
  }

  @Test
  void contactRequiredConflictMapsTo409WithADistinctTypeUrn() {
    ProblemDetail problem = handler.handleConflict(ConflictException.contactRequired());

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:contact-required")
        .isNotEqualTo("urn:agreementmitra:problem:draft-required");
    // Reworded deliberately. "email or mobile" described a rule that no longer exists - a mobile
    // alone does not make a party reachable while SMS is disabled - and "before signing" named the
    // wrong gate, since the check now first fires at order creation.
    assertThat(problem.getDetail())
        .isEqualTo("Every party needs a contact we can reach them on before payment.");
  }

  @Test
  void contactRequiredCarriesUnreachablePartiesAsStructuredDataNotInTheDetail() {
    ProblemDetail problem =
        handler.handleConflict(ConflictException.contactRequired(java.util.List.of("tenant 1")));

    // The detail stays a fixed constant, per this handler's contract that no client-facing text is
    // derived from an exception message. Which party to fix travels as a property instead, carrying
    // role-and-position labels only - never a name, address, or number.
    assertThat(problem.getDetail())
        .isEqualTo("Every party needs a contact we can reach them on before payment.");
    assertThat(problem.getProperties())
        .containsEntry("unreachableParties", java.util.List.of("tenant 1"));
  }

  @Test
  void invalidUploadMapsTo400WithConstantDetailNotReflectingMessage() {
    var ex = new InvalidUploadException("filename=evil.exe magic-byte mismatch");

    ProblemDetail problem = handler.handleInvalidUpload(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail())
        .isEqualTo("The upload must be a single file of an accepted type and size.");
    assertThat(problem.getDetail()).doesNotContain("evil.exe");
  }

  @Test
  void documentDataInvalidMapsTo400WithErrorsCarryingKeysAndRuleTokensOnly() throws Exception {
    var rejectedValue = "9999999999";
    var ex =
        new DocumentDataInvalidException(
            List.of(
                new FieldErrorDetail("monthlyRent", "min"),
                new FieldErrorDetail("purpose", "enum")));

    ProblemDetail problem = handler.handleDocumentDataInvalid(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:document-data-invalid");
    assertThat(problem.getDetail()).isEqualTo("One or more submitted fields are invalid.");

    @SuppressWarnings("unchecked")
    List<FieldErrorDetail> errors = (List<FieldErrorDetail>) problem.getProperties().get("errors");
    assertThat(errors)
        .containsExactly(
            new FieldErrorDetail("monthlyRent", "min"), new FieldErrorDetail("purpose", "enum"));
    // The serialized body carries field keys + rule tokens only, never a submitted data value.
    assertThat(mapper.writeValueAsString(problem)).doesNotContain(rejectedValue);
  }

  @Test
  void oversizedUploadMapsTo400() {
    ProblemDetail problem = handler.payloadTooLarge();

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:payload-too-large");
  }

  @Test
  void aBodyStreamedPastTheCeilingIs413OfThePayloadTooLargeType() {
    // anonymous-surface-abuse-controls D9: the guard's exception arrives wrapped by the converter.
    HttpMessageNotReadableException wrapped =
        new HttpMessageNotReadableException(
            "I/O error while reading input message",
            new RequestBodyGuard.RequestBodyTooLargeException(),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            wrapped,
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            new ServletWebRequest(new MockHttpServletRequest()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    ProblemDetail problem = (ProblemDetail) response.getBody();
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:payload-too-large");
    assertThat(problem.getInstance()).isEqualTo(problem.getType());
  }

  @Test
  void anyOtherUnreadableBodyStaysA400MalformedRequest() {
    HttpMessageNotReadableException other =
        new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));
    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            other,
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            new ServletWebRequest(new MockHttpServletRequest()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aRenderCapacityRefusalIs503RenderBusyWithRetryAfter() {
    ResponseEntity<ProblemDetail> response =
        handler.handleRenderCapacity(new RenderCapacityException("busy"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    assertThat(response.getBody().getType().toString())
        .isEqualTo("urn:agreementmitra:problem:render-busy");
    assertThat(response.getBody().getDetail()).doesNotContain("busy\"");
  }
}

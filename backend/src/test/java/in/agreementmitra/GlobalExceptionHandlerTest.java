package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

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
  void invalidUploadMapsTo400WithConstantDetailNotReflectingMessage() {
    var ex = new InvalidUploadException("filename=evil.exe magic-byte mismatch");

    ProblemDetail problem = handler.handleInvalidUpload(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("The upload must be a single PDF file.");
    assertThat(problem.getDetail()).doesNotContain("evil.exe");
  }

  @Test
  void oversizedUploadMapsTo400() {
    ProblemDetail problem = handler.payloadTooLarge();

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getType().toString())
        .isEqualTo("urn:agreementmitra:problem:payload-too-large");
  }
}

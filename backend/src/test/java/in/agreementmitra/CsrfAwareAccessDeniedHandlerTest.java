package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

@ExtendWith(OutputCaptureExtension.class)
class CsrfAwareAccessDeniedHandlerTest {

  private final CsrfAwareAccessDeniedHandler handler = new CsrfAwareAccessDeniedHandler();

  @Test
  void aCsrfExceptionGetsTheProblemBodyAndType() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new MissingCsrfTokenException(null));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("\"type\":\"urn:agreementmitra:problem:csrf\"")
        .contains("\"status\":403");
  }

  @Test
  void anInvalidTokenValueIsNeverReflectedOrLogged(CapturedOutput output) throws Exception {
    String distinctive = "ATTACKER-SUPPLIED-csrf-value-9f3e";
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(
        new MockHttpServletRequest(),
        response,
        new InvalidCsrfTokenException(
            new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), distinctive));

    assertThat(response.getContentAsString())
        .contains("urn:agreementmitra:problem:csrf")
        .doesNotContain(distinctive)
        .doesNotContain("expected");
    assertThat(output.getAll()).doesNotContain(distinctive);
  }

  @Test
  void aPlainAccessDeniedGetsTheDelegatesBare403() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("nope"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).doesNotContain("urn:agreementmitra:problem:csrf");
    assertThat(response.getContentType()).isNull();
  }
}

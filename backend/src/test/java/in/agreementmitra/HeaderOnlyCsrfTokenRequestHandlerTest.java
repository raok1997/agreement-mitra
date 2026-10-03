package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class HeaderOnlyCsrfTokenRequestHandlerTest {

  private static final CsrfToken TOKEN = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "tok");

  private final HeaderOnlyCsrfTokenRequestHandler handler = new HeaderOnlyCsrfTokenRequestHandler();

  @Test
  void theHeaderResolves() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-XSRF-TOKEN", "tok");
    assertThat(handler.resolveCsrfTokenValue(request, TOKEN)).isEqualTo("tok");
  }

  @Test
  void aParameterAloneResolvesToNull() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("_csrf", "tok");
    assertThat(handler.resolveCsrfTokenValue(request, TOKEN)).isNull();
  }

  @Test
  void handleLoadsTheTokenEagerly() {
    AtomicBoolean loaded = new AtomicBoolean();
    handler.handle(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        () -> {
          loaded.set(true);
          return TOKEN;
        });
    assertThat(loaded).isTrue();
  }
}

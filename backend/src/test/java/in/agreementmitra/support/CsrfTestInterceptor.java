package in.agreementmitra.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Adds a fixed, matching CSRF cookie + header to every unsafe request, merged into every {@code
 * Cookie} header the test already set (e.g. the session cookie). The cookie name MUST match
 * identity's {@code AuthWebConfig.csrfTokenRepository} bean in secure mode and {@code
 * SecurityConfig}'s fallback repository.
 */
public final class CsrfTestInterceptor implements ClientHttpRequestInterceptor {

  public static final String COOKIE_NAME = "__Host-XSRF-TOKEN";
  public static final String HEADER_NAME = "X-XSRF-TOKEN";
  public static final String TOKEN = "test-csrf-token";

  private static final Set<HttpMethod> SAFE =
      Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    if (!SAFE.contains(request.getMethod())) {
      apply(request.getHeaders());
    }
    return execution.execute(request, body);
  }

  /**
   * Merge every Cookie header the test set, plus the CSRF cookie, into one Cookie header and set
   * the matching CSRF header. Joining all of them matters: keeping only the first would silently
   * drop e.g. a login-binding cookie sent as a second header, and a negative test would then pass
   * for the wrong reason.
   */
  public static void apply(HttpHeaders headers) {
    String csrfCookie = COOKIE_NAME + "=" + TOKEN;
    List<String> cookies = new ArrayList<>();
    List<String> existing = headers.get(HttpHeaders.COOKIE);
    if (existing != null) {
      existing.stream().filter(value -> value != null && !value.isBlank()).forEach(cookies::add);
    }
    cookies.add(csrfCookie);
    headers.set(HttpHeaders.COOKIE, String.join("; ", cookies));
    headers.set(HEADER_NAME, TOKEN);
  }
}

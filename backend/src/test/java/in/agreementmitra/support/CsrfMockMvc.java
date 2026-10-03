package in.agreementmitra.support;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * MockMvc counterpart of {@link CsrfTestInterceptor}: sets a matching CSRF cookie + header on the
 * request. Hand-written so the suite needs no {@code spring-security-test} dependency, and there is
 * no CSRF-disable switch anywhere -- tests supply a token, they never turn the check off.
 */
public final class CsrfMockMvc {

  private CsrfMockMvc() {}

  public static RequestPostProcessor csrf() {
    return request -> {
      List<Cookie> cookies =
          new ArrayList<>(
              request.getCookies() == null ? List.of() : Arrays.asList(request.getCookies()));
      cookies.add(new Cookie(CsrfTestInterceptor.COOKIE_NAME, CsrfTestInterceptor.TOKEN));
      request.setCookies(cookies.toArray(Cookie[]::new));
      request.addHeader(CsrfTestInterceptor.HEADER_NAME, CsrfTestInterceptor.TOKEN);
      return request;
    };
  }
}

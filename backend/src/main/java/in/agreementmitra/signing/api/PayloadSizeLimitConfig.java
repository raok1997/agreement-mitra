package in.agreementmitra.signing.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds the request body size on the signing-request and webhook endpoints — a cheap DoS /
 * memory-pressure guard on the two unauthenticated, side-effecting routes. Oversized requests are
 * rejected with {@code 413} before any handler runs. (Real authorization + rate-limiting are
 * deferred to a follow-up change; this is only the body-size guard.)
 */
@Configuration(proxyBeanMethods = false)
class PayloadSizeLimitConfig {

  /** 1 MiB — generous for a webhook JSON or a bodyless create POST, tiny for an attacker. */
  private static final long MAX_BODY_BYTES = 1_048_576L;

  @Bean
  FilterRegistrationBean<PayloadSizeLimitFilter> payloadSizeLimitFilter() {
    var registration = new FilterRegistrationBean<>(new PayloadSizeLimitFilter(MAX_BODY_BYTES));
    registration.addUrlPatterns("/api/webhooks/esign", "/api/signing/*");
    return registration;
  }

  static final class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    PayloadSizeLimitFilter(long maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      long declared = request.getContentLengthLong();
      if (declared > maxBytes) {
        response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        return;
      }
      chain.doFilter(request, response);
    }
  }
}

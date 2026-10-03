package in.agreementmitra.identity.api;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.session.SessionService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * Wires the {@link SessionAuthenticationFilter} as a {@code @Bean} (not a scanned
 * {@code @Component}) so it is created only in a context that loads the identity module -- a
 * {@code @WebMvcTest} slice that auto-detects {@code Filter} beans will not try to build it without
 * {@link SessionService} present. Internal to the module.
 *
 * <p>The companion {@link FilterRegistrationBean} with {@code setEnabled(false)} stops Boot from
 * also registering the filter directly into the servlet container: it must run only inside the
 * single Spring Security chain (where {@code SecurityConfig} places it), never a second time in the
 * raw servlet chain.
 *
 * <p>Identity also owns both browser-session cookies (cookie-session-auth D6): the {@link
 * SessionCookies} helper and the {@link CsrfTokenRepository} the root security chain enforces with.
 * The CSRF cookie is defined here, beside the session cookie it protects, so rotation at login and
 * logout cannot drift from the cookie the chain reads. {@code SecurityConfig}'s fallback repository
 * and the test {@code CsrfTestInterceptor} are copies of this definition and must match it.
 */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig {

  @Bean
  CsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
    boolean secure = properties.cookie().secure();
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieName(
        secure ? SessionCookies.SECURE_CSRF_COOKIE : SessionCookies.INSECURE_CSRF_COOKIE);
    repository.setHeaderName(SessionCookies.CSRF_HEADER);
    repository.setCookieCustomizer(
        cookie ->
            cookie.secure(secure).sameSite(SessionCookies.SAME_SITE).path(SessionCookies.PATH));
    return repository;
  }

  @Bean
  SessionCookies sessionCookies(
      AuthProperties properties,
      SessionService sessionService,
      CsrfTokenRepository csrfTokenRepository) {
    return new SessionCookies(properties, sessionService, csrfTokenRepository);
  }

  @Bean
  SessionAuthenticationFilter sessionAuthenticationFilter(
      SessionService sessionService, IdentityService identityService, SessionCookies cookies) {
    return new SessionAuthenticationFilter(sessionService, identityService, cookies);
  }

  @Bean
  FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration(
      SessionAuthenticationFilter filter) {
    FilterRegistrationBean<SessionAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}

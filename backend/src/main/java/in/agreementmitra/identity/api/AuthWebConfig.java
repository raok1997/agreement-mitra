package in.agreementmitra.identity.api;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.session.SessionService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig {

  @Bean
  SessionAuthenticationFilter sessionAuthenticationFilter(
      SessionService sessionService, IdentityService identityService) {
    return new SessionAuthenticationFilter(sessionService, identityService);
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

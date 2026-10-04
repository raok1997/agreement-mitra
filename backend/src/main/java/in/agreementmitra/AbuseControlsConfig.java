package in.agreementmitra;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application-layer abuse controls (anonymous-surface-abuse-controls): the request-body
 * guard ahead of the security chain, and the route-class rate limiter inside it. Root package, like
 * {@code SecurityConfig}: the controls are cross-cutting and belong to no module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AbuseLimitsProperties.class)
class AbuseControlsConfig {

  /** The route-class limiter's bean name (recovery has its own instance). */
  static final String ROUTE_LIMITER = "routeRateLimiter";

  /**
   * Ahead of Boot's {@code FormContentFilter} (which reads a whole form-urlencoded PUT/PATCH/DELETE
   * body into memory) and therefore well ahead of the security chain (session lookup, CSRF). The
   * character-encoding filter, at the highest precedence, still runs first. Without an order the
   * guard would run after all of them.
   */
  static final int BODY_GUARD_ORDER = OrderedFormContentFilter.DEFAULT_ORDER - 10;

  @Bean
  FilterRegistrationBean<RequestBodyGuard> requestBodyGuard(
      @Value("${abuse.body.max-bytes:1048576}") long maxBytes) {
    FilterRegistrationBean<RequestBodyGuard> registration =
        new FilterRegistrationBean<>(new RequestBodyGuard(maxBytes));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(BODY_GUARD_ORDER);
    return registration;
  }

  @Bean(ROUTE_LIMITER)
  SlidingWindowRateLimiter routeRateLimiter(AbuseLimitsProperties properties) {
    return new SlidingWindowRateLimiter(properties.retention(), properties.maximumSize());
  }

  @Bean
  RouteRateLimitFilter routeRateLimitFilter(
      AbuseLimitsProperties properties,
      @Qualifier(ROUTE_LIMITER) SlidingWindowRateLimiter limiter,
      ClientSourceResolver sources,
      SecurityEvents events) {
    return new RouteRateLimitFilter(properties, limiter, sources, events);
  }

  @Bean
  CrossSiteRequestGuard crossSiteRequestGuard() {
    return new CrossSiteRequestGuard();
  }

  /** Runs only inside the security chain, like the limiter. */
  @Bean
  FilterRegistrationBean<CrossSiteRequestGuard> crossSiteRequestGuardRegistration(
      CrossSiteRequestGuard guard) {
    FilterRegistrationBean<CrossSiteRequestGuard> registration =
        new FilterRegistrationBean<>(guard);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * Stops Boot registering the limiter a second time in the raw servlet chain: it must run only
   * where {@code SecurityConfig} places it, after CSRF.
   */
  @Bean
  FilterRegistrationBean<RouteRateLimitFilter> routeRateLimitFilterRegistration(
      RouteRateLimitFilter filter) {
    FilterRegistrationBean<RouteRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}

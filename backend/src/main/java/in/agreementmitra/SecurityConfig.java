package in.agreementmitra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-wide HTTP security baseline (CR-5). Lives in the root package — NOT a sub-package —
 * so Spring Modulith does not classify it as a module (direct sub-packages of the root become
 * application modules). Fail-closed: every request is denied unless an explicit rule permits it.
 *
 * <p>No authentication mechanism exists yet; this is a posture, not an authenticator. Denied
 * requests return 403 (there is no {@code AuthenticationEntryPoint}, so no 401 challenge). The
 * webhook is permitted here because the aggregator cannot present credentials — its real
 * authorization is HMAC verification in the controller, which is deferred (not yet implemented), so
 * no webhook side-effect may ship before that lands.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                // Permit the error dispatch path. Spring Security 6 re-authorizes the internal
                // dispatch to /error that Boot performs when a handler throws; without this, a
                // PERMITTED endpoint that errors has its /error dispatch re-evaluated against
                // anyRequest().denyAll() and the real status (e.g. 500) is masked as 403. The
                // BasicErrorController leaks nothing (no stack/message by Boot default), and this
                // does not weaken the posture — every real business path is still governed below.
                auth.requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // Aggregator can't bearer-auth; authorized at the app layer via HMAC
                    // (deferred).
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/esign")
                    .permitAll()
                    // Exact stub path only — NOT /api/signing/** — so future signing sub-paths
                    // are denied by default. Tighten when an auth mechanism / real signing lands.
                    .requestMatchers(HttpMethod.POST, "/api/signing/*/request")
                    .permitAll()
                    // Sandbox agreement surface — scoped to the exact create + read-by-id paths
                    // (NOT /api/agreements/**) so future sub-paths stay denied by default. These
                    // are unauthenticated today; TEMPORARY — tighten when an auth mechanism lands.
                    .requestMatchers(HttpMethod.POST, "/api/agreements")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        // Keep Spring Security's default hardening response headers (nosniff, no-cache, etc.).
        .headers(Customizer.withDefaults())
        .build();
  }
}

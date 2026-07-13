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
 * authorization is the body-MAC (HMAC-SHA1) verification in the signing service, which runs before
 * any side effect. The signing create-request route is permitted but unauthenticated; ownership
 * authorization + rate-limiting on it are deferred to a follow-up change.
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
                    // Aggregator can't bearer-auth; authorized at the app layer via the body-MAC
                    // verification in the signing service (HMAC-SHA1 over the document id).
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/esign")
                    .permitAll()
                    // Exact create-request path only — NOT /api/signing/** — so future signing
                    // sub-paths are denied by default. This route is unauthenticated today;
                    // ownership authorization + rate-limiting are deferred to a follow-up change.
                    .requestMatchers(HttpMethod.POST, "/api/signing/*/request")
                    .permitAll()
                    // Sandbox agreement surface — scoped to the exact create + read-by-id paths
                    // (NOT /api/agreements/**) so future sub-paths stay denied by default. These
                    // are unauthenticated today; TEMPORARY — tighten when an auth mechanism lands.
                    .requestMatchers(HttpMethod.POST, "/api/agreements")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*")
                    .permitAll()
                    // Document preview (CR-3b) — scoped to the exact sub-path (NOT
                    // /api/agreements/**). Anonymous like the rest of the agreement/draft surface;
                    // TEMPORARY — tighten (ownership) when an auth mechanism lands. Renders on
                    // demand and stores nothing.
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*/preview")
                    .permitAll()
                    // Generate-as-draft (CR-3c) — render + store as the signable draft. Scoped to
                    // the exact sub-path. Anonymous like the rest of the agreement/draft surface;
                    // TEMPORARY — tighten (ownership) when an auth mechanism lands. Overwrite until
                    // signing, then locked (409) by the shared draft freeze rule.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/document")
                    .permitAll()
                    // Draft upload — scoped to the exact sub-path (NOT /api/agreements/**) so the
                    // posture stays fail-closed. Unauthenticated today; ownership authorization +
                    // rate-limiting on upload/overwrite are deferred to the signing-auth change.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/draft")
                    .permitAll()
                    // Form-projection schema (template-form-projection) — a public read of
                    // system-owned metadata (field keys/labels/widgets/validation): no PII, no
                    // signer data, no auth needed. Exact path only (NOT /api/templates/**) so
                    // future
                    // template sub-paths stay denied by default.
                    .requestMatchers(HttpMethod.GET, "/api/templates/form")
                    .permitAll()
                    // Stateless document projection (document-projection-render) -- renders a
                    // posted
                    // working set to inline HTML/PDF and stores nothing. Exact method+path only
                    // (NOT
                    // /api/templates/**) so the posture stays fail-closed. Anonymous like the rest
                    // of
                    // the render surface; server-side schema validation caps the render, and
                    // per-caller rate-limiting is an owed companion before this leaves sandbox.
                    .requestMatchers(HttpMethod.POST, "/api/templates/document/preview")
                    .permitAll()
                    // Template catalog (template-catalog) -- public reads of system-owned
                    // template metadata (no PII, no signer data, no auth needed). Exact paths
                    // only: the browse list and one entry by id. NOT /api/templates/** so future
                    // template sub-paths stay denied by default.
                    .requestMatchers(HttpMethod.GET, "/api/templates")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/templates/*")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        // Keep Spring Security's default hardening response headers (nosniff, no-cache, etc.).
        .headers(Customizer.withDefaults())
        .build();
  }
}

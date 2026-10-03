package in.agreementmitra.identity.api;

import in.agreementmitra.identity.IdentityRole;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.session.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from the opaque HttpOnly session cookie ({@code __Host-am_session}, or
 * {@code am_session} in insecure dev mode -- see {@link SessionCookies}). Part of the identity
 * module's exposed {@code api} surface (a {@link org.springframework.modulith.NamedInterface
 * NamedInterface}) so the root {@code SecurityConfig} -- the composition root -- can register it in
 * the single security filter chain without reaching into a module internal.
 *
 * <p>The cookie is the ONLY session transport (cookie-session-auth D2): an {@code Authorization}
 * header is ignored entirely, so there is a single credential path to harden and no "skip CSRF when
 * a bearer header is present" bypass.
 *
 * <p>Strictly additive to the deny-by-default posture: when the cookie is absent or the value does
 * not resolve to a live session, the filter is a NO-OP and the request continues unauthenticated
 * for the chain to handle. When it resolves, the Spring Security principal is set to the identity
 * id (a {@link UUID}); no PII becomes the principal. The value is never logged.
 *
 * <p>The authenticated principal also carries one granted authority, {@code ROLE_<role>}, read from
 * the identity record (design D7). The role is looked up server-side on every request -- it is
 * never taken from the presented session value, a request header, a body field, or an OAuth claim
 * -- so a revoked STAFF grant takes effect on the next request and privilege can never be
 * self-assigned. An identity that has vanished resolves to {@code CUSTOMER} (least privilege).
 *
 * <p>Registered as a {@code @Bean} by {@link AuthWebConfig} (not a scanned {@code @Component}) so a
 * web slice ({@code @WebMvcTest}) -- which auto-detects {@code Filter} beans -- does not try to
 * build it without the identity module present; its servlet auto-registration is disabled, so it
 * runs only inside the security chain where {@code SecurityConfig} places it.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private static final String ROLE_PREFIX = "ROLE_";

  private final SessionService sessionService;
  private final IdentityService identityService;
  private final SessionCookies cookies;

  SessionAuthenticationFilter(
      SessionService sessionService, IdentityService identityService, SessionCookies cookies) {
    this.sessionService = sessionService;
    this.identityService = identityService;
    this.cookies = cookies;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      cookies
          .read(request)
          .flatMap(sessionService::authenticate)
          .ifPresent(identityId -> authenticate(identityId, request));
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(UUID identityId, HttpServletRequest request) {
    IdentityRole role = identityService.roleOf(identityId);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            identityId, null, List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role.name())));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

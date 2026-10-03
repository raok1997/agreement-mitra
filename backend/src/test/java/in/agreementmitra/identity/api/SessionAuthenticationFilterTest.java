package in.agreementmitra.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityRole;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.session.SessionService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;

class SessionAuthenticationFilterTest {

  private final SessionService sessionService = mock(SessionService.class);
  private final IdentityService identityService = mock(IdentityService.class);
  private final SessionCookies cookies =
      new SessionCookies(
          new AuthProperties(
              "pepper",
              Duration.ofHours(1),
              Duration.ofSeconds(60),
              Duration.ofMinutes(5),
              null,
              null),
          sessionService,
          mock(CsrfTokenRepository.class));
  private final SessionAuthenticationFilter filter =
      new SessionAuthenticationFilter(sessionService, identityService, cookies);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aLiveSessionCookieAuthenticatesWithTheIdentityAndRole() throws Exception {
    UUID identityId = UUID.randomUUID();
    when(sessionService.authenticate("live")).thenReturn(Optional.of(identityId));
    when(identityService.roleOf(identityId)).thenReturn(IdentityRole.STAFF);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-am_session", "live"));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getPrincipal()).isEqualTo(identityId);
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_STAFF");
  }

  @Test
  void aBearerHeaderWithALiveValueAndNoCookieStaysUnauthenticated() throws Exception {
    when(sessionService.authenticate("live")).thenReturn(Optional.of(UUID.randomUUID()));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer live");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(sessionService, never()).authenticate(anyString());
  }

  @Test
  void anUnknownCookieIsANoOp() throws Exception {
    when(sessionService.authenticate("unknown")).thenReturn(Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-am_session", "unknown"));
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(chain.getRequest()).isSameAs(request);
  }
}

package in.agreementmitra.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Session minting/resolution (task 5.2): the minted value is persisted only as a hash (never
 * plaintext); a live session resolves to its identity and is touched; an expired/unknown session
 * does not authenticate; revoke deletes by hash. Real hasher, mocked repositories/collaborators.
 */
class SessionServiceTest {

  private static final AuthProperties PROPS =
      new AuthProperties(
          "pepper",
          Duration.ofHours(1),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          new AuthProperties.Google(
              "client", "secret", "redirect", "spa", "issuer", "auth", "token", "jwks"));

  private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
  private final HandoffService handoffService = mock(HandoffService.class);
  private final IdentityService identityService = mock(IdentityService.class);
  private final SecretTokens secretTokens = mock(SecretTokens.class);
  private final TokenHasher hasher = new TokenHasher(PROPS);
  private final SessionService service =
      new SessionService(sessions, handoffService, identityService, secretTokens, hasher, PROPS);

  @Test
  void exchangePersistsOnlyTheHashNotThePlaintextValue() {
    UUID identityId = UUID.randomUUID();
    when(handoffService.consume("handoff")).thenReturn(identityId);
    when(identityService.summary(identityId))
        .thenReturn(
            Optional.of(
                new IdentitySummary(
                    identityId,
                    "Asha",
                    "a@x.com",
                    in.agreementmitra.identity.IdentityRole.CUSTOMER)));
    when(secretTokens.newToken()).thenReturn("plaintext-session-value");

    SessionService.SessionIssued issued = service.exchange("handoff");

    assertThat(issued.value()).isEqualTo("plaintext-session-value");
    ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
    verify(sessions).save(captor.capture());
    // Only the hash is persisted; the plaintext value never appears on the row.
    assertThat(captor.getValue().toString()).doesNotContain("plaintext-session-value");
    assertThat(captor.getValue().identityId()).isEqualTo(identityId);
  }

  @Test
  void authenticateResolvesALiveSessionToItsIdentityAndTouchesIt() {
    UUID identityId = UUID.randomUUID();
    AuthSession live =
        AuthSession.create(identityId, hasher.hash("value"), Instant.now().plusSeconds(600));
    when(sessions.findByValueHash(hasher.hash("value"))).thenReturn(Optional.of(live));

    Optional<UUID> resolved = service.authenticate("value");

    assertThat(resolved).contains(identityId);
    verify(sessions).save(live); // last-seen touch persisted
  }

  @Test
  void authenticateDoesNotResolveAnExpiredSession() {
    AuthSession expired =
        AuthSession.create(UUID.randomUUID(), hasher.hash("value"), Instant.now().minusSeconds(1));
    when(sessions.findByValueHash(hasher.hash("value"))).thenReturn(Optional.of(expired));

    assertThat(service.authenticate("value")).isEmpty();
    verify(sessions, never()).save(any());
  }

  @Test
  void authenticateDoesNotResolveAnUnknownValue() {
    when(sessions.findByValueHash(any())).thenReturn(Optional.empty());
    assertThat(service.authenticate("value")).isEmpty();
  }

  @Test
  void authenticateIgnoresAnAbsentBearer() {
    assertThat(service.authenticate(null)).isEmpty();
    assertThat(service.authenticate("  ")).isEmpty();
    verify(sessions, never()).findByValueHash(any());
  }

  @Test
  void revokeDeletesByHash() {
    service.revoke("value");
    verify(sessions).deleteByValueHash(hasher.hash("value"));
  }
}

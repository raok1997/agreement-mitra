package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Handoff lifecycle (task 5.3): issue stores only a hash (never the raw code); consume is
 * single-use -- a won race returns the identity, a lost race (already-consumed or expired -> the
 * conditional UPDATE affects 0 rows) is refused.
 */
class HandoffServiceTest {

  private static final AuthProperties PROPS =
      new AuthProperties(
          "pepper",
          Duration.ofHours(1),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          new AuthProperties.Google(
              "client", "secret", "redirect", "spa", "issuer", "auth", "token", "jwks"));

  private final LoginHandoffRepository handoffs = mock(LoginHandoffRepository.class);
  private final SecretTokens secretTokens = mock(SecretTokens.class);
  private final TokenHasher hasher = new TokenHasher(PROPS);
  private final HandoffService service = new HandoffService(handoffs, secretTokens, hasher, PROPS);

  @Test
  void issueStoresOnlyTheHashAndReturnsTheRawCode() {
    when(secretTokens.newToken()).thenReturn("raw-handoff-code");
    UUID identityId = UUID.randomUUID();

    String raw = service.issue(identityId);

    assertThat(raw).isEqualTo("raw-handoff-code");
    ArgumentCaptor<LoginHandoff> captor = ArgumentCaptor.forClass(LoginHandoff.class);
    org.mockito.Mockito.verify(handoffs).save(captor.capture());
    LoginHandoff saved = captor.getValue();
    // The row carries the hash, never the raw code.
    assertThat(saved.toString()).doesNotContain("raw-handoff-code");
    assertThat(saved.identityId()).isEqualTo(identityId);
  }

  @Test
  void consumeReturnsTheIdentityWhenItWinsTheSingleUseRace() {
    UUID identityId = UUID.randomUUID();
    String hash = hasher.hash("raw");
    when(handoffs.consume(eq(hash), any(Instant.class))).thenReturn(1);
    LoginHandoff row = LoginHandoff.create(hash, identityId, Instant.now().plusSeconds(60));
    when(handoffs.findByHandoffHash(hash)).thenReturn(Optional.of(row));

    assertThat(service.consume("raw")).isEqualTo(identityId);
  }

  @Test
  void consumeIsRefusedWhenAlreadyConsumedOrExpired() {
    when(handoffs.consume(any(), any(Instant.class))).thenReturn(0);

    assertThatThrownBy(() -> service.consume("raw")).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void consumeRejectsABlankHandoff() {
    assertThatThrownBy(() -> service.consume("  ")).isInstanceOf(InvalidLoginException.class);
  }
}

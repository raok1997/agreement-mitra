package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.GoogleLoginService.StartRedirect;
import in.agreementmitra.identity.oauth.GoogleTokenValidator.GoogleIdentity;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The login-binding nonce through {@link GoogleLoginService} (login-browser-binding D2-D4): start
 * stores only its hash and hands back the raw value; the callback refuses a missing nonce before
 * any repository call, refuses a lost consume before calling Google, and binds the handoff to the
 * same raw nonce.
 */
class GoogleLoginServiceBindingTest {

  private static final AuthProperties PROPS =
      new AuthProperties(
          "pepper",
          Duration.ofHours(1),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          new AuthProperties.Google(
              "client", "secret", "redirect", "spa", "issuer", "http://auth", "token", "jwks"),
          null);

  private final OauthLoginStateRepository loginStates = mock(OauthLoginStateRepository.class);
  private final HandoffService handoffService = mock(HandoffService.class);
  private final GoogleTokenExchange tokenExchange = mock(GoogleTokenExchange.class);
  private final GoogleTokenValidator tokenValidator = mock(GoogleTokenValidator.class);
  private final IdentityService identityService = mock(IdentityService.class);
  private final SecretTokens secretTokens = mock(SecretTokens.class);
  private final TokenHasher hasher = new TokenHasher(PROPS);
  private final GoogleLoginService service =
      new GoogleLoginService(
          loginStates,
          handoffService,
          tokenExchange,
          tokenValidator,
          identityService,
          secretTokens,
          hasher,
          PROPS);

  @Test
  void startStoresOnlyTheNonceHashAndReturnsTheRawNonce() {
    when(secretTokens.newToken()).thenReturn("raw-state", "raw-verifier", "raw-nonce");
    when(secretTokens.pkceChallenge("raw-verifier")).thenReturn("challenge");

    StartRedirect redirect = service.start();

    assertThat(redirect.bindingNonce()).isEqualTo("raw-nonce");
    assertThat(redirect.authorizationUri()).doesNotContain("raw-nonce");
    ArgumentCaptor<OauthLoginState> saved = ArgumentCaptor.forClass(OauthLoginState.class);
    verify(loginStates).save(saved.capture());
    assertThat(saved.getValue().browserBindingHash()).isEqualTo(hasher.hash("raw-nonce"));
  }

  @Test
  void startRedirectToStringOmitsTheNonceAndTheState() {
    StartRedirect redirect = new StartRedirect("http://auth?state=raw-state", "raw-nonce");

    assertThat(redirect.toString()).doesNotContain("raw-nonce").doesNotContain("raw-state");
  }

  @Test
  void handoffIssuedToStringOmitsTheHandoff() {
    assertThat(new GoogleLoginService.HandoffIssued("raw-handoff").toString())
        .doesNotContain("raw-handoff");
  }

  @Test
  void aCallbackWithoutANonceIsRefusedBeforeTheRepository() {
    for (String nonce : new String[] {null, "", "  "}) {
      assertThatThrownBy(() -> service.handleCallback("code", "state", nonce))
          .isInstanceOf(InvalidLoginException.class)
          .hasMessage("login binding missing");
    }
    verifyNoInteractions(loginStates, tokenExchange, handoffService);
  }

  @Test
  void aLostConsumeIsRefusedWithoutCallingGoogle() {
    when(loginStates.consume(eq(hasher.hash("state")), eq(hasher.hash("other")), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.handleCallback("code", "state", "other"))
        .isInstanceOf(InvalidLoginException.class);

    verifyNoInteractions(tokenExchange, handoffService);
    verify(loginStates, never()).findByStateHash(anyString());
  }

  @Test
  void theHandoffIsIssuedWithTheSameRawNonce() {
    String stateHash = hasher.hash("state");
    when(loginStates.consume(eq(stateHash), eq(hasher.hash("raw-nonce")), any(Instant.class)))
        .thenReturn(1);
    when(loginStates.findByStateHash(stateHash))
        .thenReturn(
            Optional.of(
                OauthLoginState.create(
                    stateHash,
                    "verifier",
                    "redirect",
                    Instant.now().plusSeconds(60),
                    hasher.hash("raw-nonce"))));
    when(tokenExchange.exchangeForIdToken("code", "verifier")).thenReturn("id-token");
    when(tokenValidator.validate("id-token"))
        .thenReturn(new GoogleIdentity("sub", "a@x.com", "Asha"));
    UUID identityId = UUID.randomUUID();
    when(identityService.findOrCreate("GOOGLE", "sub", "a@x.com", true, "Asha"))
        .thenReturn(identityId);
    when(handoffService.issue(identityId, "raw-nonce")).thenReturn("handoff");

    assertThat(service.handleCallback("code", "state", "raw-nonce").handoff()).isEqualTo("handoff");
    verify(handoffService).issue(identityId, "raw-nonce");
  }
}

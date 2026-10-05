package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code toString()} is id-only for the handshake rows -- never a hash (state, handoff or browser
 * binding) or PKCE verifier (task 5.5; login-browser-binding 1.2).
 */
class OauthEntitiesToStringTest {

  @Test
  void loginStateToStringIsIdOnly() {
    OauthLoginState state =
        OauthLoginState.create(
            "state-hash-value",
            "pkce-code-verifier",
            "redirect",
            Instant.now().plusSeconds(300),
            "binding-hash-value");

    assertThat(state.toString())
        .contains(state.getId().toString())
        .doesNotContain("state-hash-value")
        .doesNotContain("pkce-code-verifier")
        .doesNotContain("binding-hash-value");
  }

  @Test
  void handoffToStringIsIdOnly() {
    UUID identityId = UUID.randomUUID();
    LoginHandoff handoff =
        LoginHandoff.create(
            "handoff-hash-value", identityId, Instant.now().plusSeconds(60), "binding-hash-value");

    assertThat(handoff.toString())
        .contains(handoff.getId().toString())
        .doesNotContain("handoff-hash-value")
        .doesNotContain(identityId.toString())
        .doesNotContain("binding-hash-value");
  }
}

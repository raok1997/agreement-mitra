package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code toString()} is id-only for the handshake rows -- never a hash or PKCE verifier (task 5.5).
 */
class OauthEntitiesToStringTest {

  @Test
  void loginStateToStringIsIdOnly() {
    OauthLoginState state =
        OauthLoginState.create(
            "state-hash-value", "pkce-code-verifier", "redirect", Instant.now().plusSeconds(300));

    assertThat(state.toString())
        .contains(state.getId().toString())
        .doesNotContain("state-hash-value")
        .doesNotContain("pkce-code-verifier");
  }

  @Test
  void handoffToStringIsIdOnly() {
    UUID identityId = UUID.randomUUID();
    LoginHandoff handoff =
        LoginHandoff.create("handoff-hash-value", identityId, Instant.now().plusSeconds(60));

    assertThat(handoff.toString())
        .contains(handoff.getId().toString())
        .doesNotContain("handoff-hash-value")
        .doesNotContain(identityId.toString());
  }
}

package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code toString()} is id-only for the identity aggregate + credential (task 5.5). */
class IdentityToStringTest {

  @Test
  void identityToStringIsIdOnly() {
    Identity identity = Identity.create("Asha Rao", "alice@gmail.com");

    assertThat(identity.toString())
        .contains(identity.getId().toString())
        .doesNotContain("Asha")
        .doesNotContain("alice")
        .doesNotContain("gmail");
  }

  @Test
  void credentialToStringIsIdOnly() {
    IdentityCredential credential =
        IdentityCredential.create(
            UUID.randomUUID(), "GOOGLE", "google-sub-xyz", "alice@gmail.com", true);

    assertThat(credential.toString())
        .contains(credential.getId().toString())
        .doesNotContain("google-sub-xyz")
        .doesNotContain("alice")
        .doesNotContain("gmail");
  }
}

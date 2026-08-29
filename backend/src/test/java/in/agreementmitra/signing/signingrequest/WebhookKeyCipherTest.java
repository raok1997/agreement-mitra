package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the at-rest encryption of the per-transaction webhook key.
 *
 * <p>The point of this component is a single property: a database read must not yield a working
 * webhook credential. Everything here is checking that property, not the cipher itself.
 */
class WebhookKeyCipherTest {

  private static final String KEY = "3f7c1c9a-77bd-4a1f-9d2e-2c5b8a6e4d10";

  private final WebhookKeyCipher cipher =
      new WebhookKeyCipher(new WebhookKeyProperties("unit-test-pepper"));

  @Test
  void roundTripsTheKey() {
    assertThat(cipher.decrypt(cipher.encrypt(KEY))).isEqualTo(KEY);
  }

  @Test
  void theStoredValueNeverContainsThePlaintext() {
    String stored = cipher.encrypt(KEY);

    assertThat(stored).isNotNull().isNotEqualTo(KEY).doesNotContain(KEY);
  }

  @Test
  void theSameKeyEncryptsDifferentlyEveryTime() {
    // A fresh random IV per encryption: two rows holding the same vendor key must not be visibly
    // identical, and a repeated ciphertext would leak that.
    assertThat(cipher.encrypt(KEY)).isNotEqualTo(cipher.encrypt(KEY));
  }

  @Test
  void aDifferentPepperCannotDecryptIt() {
    String stored = cipher.encrypt(KEY);

    WebhookKeyCipher other = new WebhookKeyCipher(new WebhookKeyProperties("a-different-pepper"));

    assertThat(other.decrypt(stored)).isNull();
  }

  @Test
  void aTamperedStoredValueFailsClosedRatherThanYieldingSomething() {
    // GCM is authenticated encryption, so a modified row does not decrypt to attacker-chosen bytes.
    String stored = cipher.encrypt(KEY);
    String tampered =
        stored.substring(0, stored.length() - 2) + (stored.endsWith("A") ? "BB" : "AA");

    assertThat(cipher.decrypt(tampered)).isNull();
  }

  @Test
  void garbageDecryptsToNullRatherThanThrowingOnAPublicEndpoint() {
    // The webhook endpoint is public; a corrupt row must fail verification, not raise a 500.
    assertThat(cipher.decrypt("not base64 !!")).isNull();
    assertThat(cipher.decrypt("c2hvcnQ=")).isNull(); // valid base64, too short to hold an IV
  }

  @Test
  void aProviderThatIssuesNoKeyStoresNothing() {
    assertThat(cipher.encrypt(null)).isNull();
    assertThat(cipher.encrypt("")).isNull();
    assertThat(cipher.decrypt(null)).isNull();
    assertThat(cipher.decrypt("  ")).isNull();
  }

  @Test
  void thePepperIsNeverRendered() {
    assertThat(new WebhookKeyProperties("super-pepper").toString()).doesNotContain("super-pepper");
  }
}

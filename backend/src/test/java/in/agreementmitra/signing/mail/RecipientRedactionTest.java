package in.agreementmitra.signing.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Redaction of recipient addresses in delivery log lines. The delivery path handles party mailboxes
 * on every attempt, so the one thing that must never happen is a full address landing in a log
 * file.
 */
class RecipientRedactionTest {

  @Test
  void masksTheLocalPartAndKeepsTheDomain() {
    assertThat(RecipientRedaction.redact("asha@example.com")).isEqualTo("a***@example.com");
    assertThat(RecipientRedaction.redact("t@example.co.in")).isEqualTo("t***@example.co.in");
  }

  @Test
  void neverEchoesAnythingItCannotSafelyMask() {
    // Fail closed: anything without a usable local part AND a domain collapses entirely, rather
    // than being passed through on the theory that it is probably not an address.
    assertThat(RecipientRedaction.redact(null)).isEqualTo("***");
    assertThat(RecipientRedaction.redact("")).isEqualTo("***");
    assertThat(RecipientRedaction.redact("   ")).isEqualTo("***");
    assertThat(RecipientRedaction.redact("no-at-sign")).isEqualTo("***");
    assertThat(RecipientRedaction.redact("@example.com")).isEqualTo("***");
    assertThat(RecipientRedaction.redact("asha@")).isEqualTo("***");
  }

  @Test
  void neverReturnsTheRawAddress() {
    String raw = "someone.long.name@example.com";
    assertThat(RecipientRedaction.redact(raw)).isNotEqualTo(raw).doesNotContain("omeone.long.name");
  }
}

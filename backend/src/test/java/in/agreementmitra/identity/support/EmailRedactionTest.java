package in.agreementmitra.identity.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The shared email-redaction helper: mask the local part, never leak a full address (task 5.5). */
class EmailRedactionTest {

  @Test
  void masksTheLocalPartKeepingFirstCharAndDomain() {
    assertThat(EmailRedaction.redact("alice@gmail.com")).isEqualTo("a***@gmail.com");
  }

  @Test
  void neverLeaksTheFullLocalPart() {
    String redacted = EmailRedaction.redact("sensitive.person@example.org");
    assertThat(redacted).isEqualTo("s***@example.org").doesNotContain("sensitive.person");
  }

  @Test
  void collapsesNullBlankOrMalformedToAConstant() {
    assertThat(EmailRedaction.redact(null)).isEqualTo("***");
    assertThat(EmailRedaction.redact("")).isEqualTo("***");
    assertThat(EmailRedaction.redact("   ")).isEqualTo("***");
    assertThat(EmailRedaction.redact("no-at-sign")).isEqualTo("***");
    assertThat(EmailRedaction.redact("@nolocalpart.com")).isEqualTo("***");
    assertThat(EmailRedaction.redact("trailing@")).isEqualTo("***");
  }
}

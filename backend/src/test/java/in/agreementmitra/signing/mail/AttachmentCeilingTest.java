package in.agreementmitra.signing.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.signing.EmailAttachment;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import org.junit.jupiter.api.Test;

/**
 * The attachment ceiling and its derivation.
 *
 * <p>The number matters, and so does what it is measured against. ZeptoMail caps a message at 15 MB
 * in total - headers, body and base64-encoded attachments combined - and base64 inflates binary by
 * roughly a third. Measuring the raw file against 15 MB would therefore produce a message the
 * provider rejects <em>after</em> we had recorded it as sent, and the oversize fallback would never
 * fire. 10 MiB raw assembles to roughly 13.4 MB, which leaves real headroom.
 */
class AttachmentCeilingTest {

  private static final long FIFTEEN_MB = 15L * 1000 * 1000;

  private static AttachmentCeiling ceiling(long maxBytes) {
    return new AttachmentCeiling(
        new OutboundMailProperties(null, "from@example.com", null, maxBytes, null));
  }

  @Test
  void theDefaultRawCeilingAssemblesWellInsideTheProviderMessageLimit() {
    long raw = OutboundMailProperties.DEFAULT_MAX_ATTACHMENT_BYTES;
    // base64 is 4 bytes out for every 3 in.
    long encoded = raw * 4 / 3;
    assertThat(encoded).isLessThan(FIFTEEN_MB);
    // ...and with real headroom left for headers, the body and MIME boundaries, not a hair's width.
    assertThat(FIFTEEN_MB - encoded).isGreaterThan(1_000_000L);
  }

  @Test
  void theDefaultCeilingSitsAboveTheCertificateScanIntakeCeiling() {
    // Chosen together with the 8 MiB scan ceiling in the stamp-intake path: the scan is the one
    // input to the signed PDF with no natural size bound, so the attachment ceiling has to sit
    // above it or a maximal-but-legal scan would always fall back.
    assertThat(OutboundMailProperties.DEFAULT_MAX_ATTACHMENT_BYTES).isGreaterThan(8L * 1024 * 1024);
  }

  @Test
  void reportsWhetherARawAttachmentExceedsTheCeiling() {
    AttachmentCeiling ceiling = ceiling(1000);
    assertThat(ceiling.maxBytes()).isEqualTo(1000);
    assertThat(ceiling.exceededBy(1000)).isFalse();
    assertThat(ceiling.exceededBy(1001)).isTrue();
  }

  @Test
  void anOversizeAttachmentIsRefusedPermanentlyAtTheSeam() {
    // The backstop: no caller can hand a provider an oversize attachment by going round the
    // delivery path. Permanent, because retrying sends exactly the same too-large document.
    EmailMessage message =
        new EmailMessage(
            "asha@example.com",
            "Your signed rental agreement",
            "body",
            new EmailAttachment("signed.pdf", "application/pdf", new byte[1001]));

    assertThatThrownBy(() -> ceiling(1000).enforce(message))
        .isInstanceOf(EmailDeliveryException.class)
        .extracting(e -> ((EmailDeliveryException) e).permanent())
        .isEqualTo(true);
  }

  @Test
  void aMessageWithinTheCeilingOrWithNoAttachmentPasses() {
    AttachmentCeiling ceiling = ceiling(1000);
    ceiling.enforce(
        new EmailMessage(
            "asha@example.com",
            "s",
            "b",
            new EmailAttachment("signed.pdf", "application/pdf", new byte[1000])));
    ceiling.enforce(new EmailMessage("asha@example.com", "s", "b"));
  }
}

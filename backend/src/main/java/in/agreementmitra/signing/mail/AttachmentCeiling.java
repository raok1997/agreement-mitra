package in.agreementmitra.signing.mail;

import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The attachment size ceiling, expressed once and enforced at the seam.
 *
 * <p>The value is derived from the provider's <b>assembled-message</b> limit, not from the raw file
 * (design D7a): ZeptoMail caps a message at 15 MB including headers and base64 encoding, and base64
 * inflates binary content by roughly a third, so the raw PDF is capped around 10 MiB. Getting this
 * backwards produces a message the provider rejects <em>after</em> we have recorded it as sent.
 *
 * <p>Enforced in two places on purpose. The delivery path asks {@link #exceededBy(int)} first, so
 * an oversize document falls back to a notification pointing at the in-app copy and the condition
 * is recorded (design D6). {@link #enforce(EmailMessage)} is the backstop inside the adapters, so
 * no future caller can hand the provider an oversize attachment by going round the delivery path. A
 * truncated attachment is never an option in either place.
 */
@Component
@EnableConfigurationProperties(OutboundMailProperties.class)
public class AttachmentCeiling {

  private final long maxBytes;

  AttachmentCeiling(OutboundMailProperties properties) {
    this.maxBytes = properties.maxAttachmentBytes();
  }

  /** The configured raw-attachment ceiling in bytes. */
  public long maxBytes() {
    return maxBytes;
  }

  /** Whether a raw attachment of {@code bytes} would exceed the ceiling. */
  public boolean exceededBy(int bytes) {
    return bytes > maxBytes;
  }

  /**
   * Backstop before a message reaches a provider. An oversize attachment is a <b>permanent</b>
   * failure: retrying sends the same too-large document, so escalating is the only honest outcome.
   */
  void enforce(EmailMessage message) {
    if (message.hasAttachment() && exceededBy(message.attachment().size())) {
      throw EmailDeliveryException.permanentFailure("attachment-exceeds-ceiling", null);
    }
  }
}

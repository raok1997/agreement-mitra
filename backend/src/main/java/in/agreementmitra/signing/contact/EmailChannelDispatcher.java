package in.agreementmitra.signing.contact;

import in.agreementmitra.signing.EmailAttachment;
import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import org.springframework.stereotype.Component;

/**
 * The email adapter, and the only channel with one. Delegates to the existing {@code EmailSender}
 * seam rather than opening a second path to a mail provider - which keeps the stub-by-default
 * posture, the attachment ceiling, and the recipient redaction that seam already enforces.
 *
 * <p>Body-only by construction: recovery messages carry a reference and a link, never a document.
 */
@Component
class EmailChannelDispatcher implements ChannelDispatcher {

  private final EmailSender emailSender;

  EmailChannelDispatcher(EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  @Override
  public DeliveryChannel channel() {
    return DeliveryChannel.EMAIL;
  }

  @Override
  public void send(ChannelMessage message) {
    emailSender.send(
        new EmailMessage(
            message.destination(),
            message.subject(),
            message.body(),
            toAttachment(message.attachment())));
  }

  private static EmailAttachment toAttachment(ChannelMessage.ChannelAttachment attachment) {
    return attachment == null
        ? null
        : new EmailAttachment(
            attachment.filename(), attachment.contentType(), attachment.content());
  }
}

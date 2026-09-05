package in.agreementmitra.signing.mail;

import in.agreementmitra.signing.EmailAttachment;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The <b>one</b> SMTP adapter behind the {@link EmailSender} seam (design D7). It serves free Zoho
 * Mail in development and Zoho ZeptoMail in production, because both speak SMTP: host, port,
 * username and password are all configuration, so the production upgrade is credentials rather than
 * a second adapter.
 *
 * <p>Only active when {@code mail.provider=smtp}; the stub is the default.
 *
 * <p><b>Failure classification is the interesting part.</b> A rejected address is permanent -
 * retrying a mailbox that does not exist only postpones the moment a human looks at it - while a
 * refused connection, timeout or unavailable provider is transient and worth retrying with backoff.
 * When SMTP does not tell us which it is, the adapter classifies conservatively as transient: the
 * bounded attempt limit turns a wrong guess into a delay, whereas guessing "permanent" would strand
 * a deliverable document.
 *
 * <p><b>What SMTP cannot tell us at all is whether the message arrived.</b> A successful {@code
 * send} means the provider accepted it. Hard bounces are invisible over plain SMTP; ZeptoMail's
 * bounce webhook closes that in production and is deliberately not built here, because there is
 * nothing in development to receive from. Nothing in this class may be read as proof of delivery.
 *
 * <p>Logging carries a redacted recipient and a fixed failure token only - never the body, the
 * attachment bytes, the provider's message, or any credential.
 */
@Component
@EnableConfigurationProperties(OutboundMailProperties.class)
@ConditionalOnProperty(prefix = "mail", name = "provider", havingValue = "smtp")
class SmtpEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

  private final JavaMailSender mailSender;
  private final OutboundMailProperties properties;
  private final AttachmentCeiling ceiling;

  SmtpEmailSender(
      JavaMailSender mailSender, OutboundMailProperties properties, AttachmentCeiling ceiling) {
    this.mailSender = mailSender;
    this.properties = properties;
    this.ceiling = ceiling;
  }

  @Override
  public void send(EmailMessage message) {
    // Backstop before assembly: an oversize attachment must never reach the provider, because a
    // provider-side rejection would arrive after we had already recorded the message as sent.
    ceiling.enforce(message);
    MimeMessage mime = mailSender.createMimeMessage();
    try {
      MimeMessageHelper helper =
          new MimeMessageHelper(mime, message.hasAttachment(), StandardCharsets.UTF_8.name());
      setFrom(helper);
      helper.setTo(message.to());
      helper.setSubject(message.subject());
      helper.setText(message.body(), false);
      if (message.hasAttachment()) {
        EmailAttachment attachment = message.attachment();
        helper.addAttachment(
            attachment.filename(),
            new ByteArrayResource(attachment.content()),
            attachment.contentType());
      }
    } catch (jakarta.mail.MessagingException | UnsupportedEncodingException e) {
      // The message could not even be assembled. Retrying assembles the same thing.
      throw EmailDeliveryException.permanentFailure("message-not-assemblable", e);
    }
    try {
      mailSender.send(mime);
    } catch (MailException e) {
      throw classify(e);
    }
    log.debug("SMTP seam accepted a message for {}", RecipientRedaction.redact(message.to()));
  }

  private void setFrom(MimeMessageHelper helper)
      throws jakarta.mail.MessagingException, UnsupportedEncodingException {
    String from = properties.from();
    if (from == null || from.isBlank()) {
      // Sending from nothing would either fail confusingly at the provider or send from a default
      // nobody controls. Refuse up front, permanently, so the misconfiguration is visible.
      throw EmailDeliveryException.permanentFailure("sender-address-not-configured", null);
    }
    helper.setFrom(from, properties.fromName());
  }

  /**
   * Map a Spring mail failure onto the transient/permanent split the delivery lifecycle needs.
   *
   * <p>Permanent: the recipient address was rejected (SMTP reported it invalid), or the message
   * itself could not be parsed or prepared. Transient: everything else, including authentication
   * failures - a wrong credential is a configuration problem that a later attempt can succeed
   * after, and the bounded attempt limit stops it retrying forever.
   */
  private static EmailDeliveryException classify(MailException e) {
    if (e instanceof MailParseException || e instanceof MailPreparationException) {
      return EmailDeliveryException.permanentFailure("message-rejected", e);
    }
    if (e instanceof MailAuthenticationException) {
      return EmailDeliveryException.transientFailure("provider-authentication-failed", e);
    }
    if (e instanceof MailSendException sendFailure && hasInvalidAddress(sendFailure)) {
      return EmailDeliveryException.permanentFailure("recipient-address-rejected", e);
    }
    return EmailDeliveryException.transientFailure("provider-unavailable", e);
  }

  /** True when SMTP explicitly reported the recipient address as invalid. */
  private static boolean hasInvalidAddress(MailSendException e) {
    for (Exception failure : e.getFailedMessages().values()) {
      for (Throwable t = failure; t != null; t = t.getCause()) {
        if (t instanceof SendFailedException sendFailed) {
          var invalid = sendFailed.getInvalidAddresses();
          if (invalid != null && invalid.length > 0) {
            return true;
          }
        }
      }
    }
    return false;
  }
}

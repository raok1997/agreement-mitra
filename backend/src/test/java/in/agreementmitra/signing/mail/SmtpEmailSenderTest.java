package in.agreementmitra.signing.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.EmailAttachment;
import in.agreementmitra.signing.EmailDeliveryException;
import in.agreementmitra.signing.EmailMessage;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The SMTP adapter's <b>failure classification</b> - the part of it that decides whether waiting
 * will help.
 *
 * <p>This is what the delivery lifecycle keys off: a permanent failure stops and escalates to a
 * human, a transient one retries with backoff. Getting it backwards either strands a deliverable
 * document (permanent when it was not) or retries a mailbox that does not exist until nobody is
 * watching any more (transient when it was not).
 *
 * <p>No live mailbox, credential, or network access: the transport is mocked and the assertions are
 * about classification, not about SMTP.
 */
@ExtendWith(MockitoExtension.class)
class SmtpEmailSenderTest {

  @Mock private JavaMailSender mailSender;

  private static final EmailMessage MESSAGE =
      new EmailMessage(
          "asha@example.com",
          "Your signed rental agreement",
          "body",
          new EmailAttachment("signed-rental-agreement.pdf", "application/pdf", new byte[] {1, 2}));

  private SmtpEmailSender sender(long ceilingBytes, String from) {
    OutboundMailProperties properties =
        new OutboundMailProperties(
            OutboundMailProperties.Provider.SMTP, from, null, ceilingBytes, null);
    return new SmtpEmailSender(mailSender, properties, new AttachmentCeiling(properties));
  }

  private SmtpEmailSender sender() {
    return sender(1024, "no-reply@example.com");
  }

  private void stubMimeMessage() {
    when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
  }

  @Test
  void aRejectedRecipientAddressIsPermanent() throws Exception {
    stubMimeMessage();
    SendFailedException rejected =
        new SendFailedException(
            "invalid",
            new Exception(),
            new InternetAddress[0],
            new InternetAddress[0],
            new InternetAddress[] {new InternetAddress("nobody@example.com")});
    doThrow(new MailSendException(Map.of(new Object(), rejected)))
        .when(mailSender)
        .send(any(MimeMessage.class));

    assertThatThrownBy(() -> sender().send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .satisfies(
            e -> {
              assertThat(((EmailDeliveryException) e).permanent()).isTrue();
              assertThat(((EmailDeliveryException) e).reason())
                  .isEqualTo("recipient-address-rejected");
            });
  }

  @Test
  void anUnreachableProviderIsTransient() {
    stubMimeMessage();
    // No invalid address reported: the message is fine, the transport is not. Retrying may work.
    doThrow(new MailSendException("connection refused"))
        .when(mailSender)
        .send(any(MimeMessage.class));

    assertThatThrownBy(() -> sender().send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .extracting(e -> ((EmailDeliveryException) e).permanent())
        .isEqualTo(false);
  }

  @Test
  void anAuthenticationFailureIsTransientBecauseItIsFixableConfiguration() {
    stubMimeMessage();
    doThrow(new MailAuthenticationException("bad credential"))
        .when(mailSender)
        .send(any(MimeMessage.class));

    assertThatThrownBy(() -> sender().send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .satisfies(
            e -> {
              // Bounded by the attempt limit, so a wrong credential becomes a visible failed
              // delivery rather than an infinite retry loop.
              assertThat(((EmailDeliveryException) e).permanent()).isFalse();
              assertThat(((EmailDeliveryException) e).reason())
                  .isEqualTo("provider-authentication-failed");
            });
  }

  @Test
  void anUnparseableMessageIsPermanent() {
    stubMimeMessage();
    doThrow(new MailParseException("bad message")).when(mailSender).send(any(MimeMessage.class));

    assertThatThrownBy(() -> sender().send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .extracting(e -> ((EmailDeliveryException) e).permanent())
        .isEqualTo(true);
  }

  @Test
  void anOversizeAttachmentNeverReachesTheProvider() {
    // The ceiling backstop fires before assembly, so the transport is never touched at all.
    assertThatThrownBy(() -> sender(1, "no-reply@example.com").send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .extracting(e -> ((EmailDeliveryException) e).reason())
        .isEqualTo("attachment-exceeds-ceiling");
    verify(mailSender, org.mockito.Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void anUnconfiguredSenderAddressFailsPermanentlyRatherThanSendingFromSomethingArbitrary() {
    stubMimeMessage();
    assertThatThrownBy(() -> sender(1024, "").send(MESSAGE))
        .isInstanceOf(EmailDeliveryException.class)
        .satisfies(
            e -> {
              assertThat(((EmailDeliveryException) e).permanent()).isTrue();
              assertThat(((EmailDeliveryException) e).reason())
                  .isEqualTo("sender-address-not-configured");
            });
    verify(mailSender, org.mockito.Mockito.never()).send(any(MimeMessage.class));
  }

  @Test
  void aSuccessfulSendHandsExactlyOneMessageToTheTransport() {
    stubMimeMessage();
    sender().send(MESSAGE);
    verify(mailSender).send(any(MimeMessage.class));
  }
}

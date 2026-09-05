package in.agreementmitra.signing.mail;

import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The default {@link EmailSender}: captures messages in memory and <b>sends nothing</b>.
 *
 * <p>Active by default ({@code mail.provider} defaults to {@code stub}), which is the point. The
 * whole test suite runs with no mailbox, no credential, and no outbound connection, and a local run
 * cannot accidentally email a real person a document containing two parties' names, a property
 * address and financial terms. Real sending is configuration, never a default.
 *
 * <p>Captured messages are held in a small bounded ring so a long-running local session cannot grow
 * the heap. Nothing about the message is logged beyond a redacted recipient and whether an
 * attachment was present - never the body, never the attachment bytes.
 */
@Component
@EnableConfigurationProperties(OutboundMailProperties.class)
@ConditionalOnProperty(
    prefix = "mail",
    name = "provider",
    havingValue = "stub",
    matchIfMissing = true)
public class StubEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(StubEmailSender.class);

  /** Bounded so a local session cannot accumulate documents in memory indefinitely. */
  private static final int CAPACITY = 100;

  private final Deque<EmailMessage> captured = new ArrayDeque<>();
  private final AttachmentCeiling ceiling;

  StubEmailSender(AttachmentCeiling ceiling) {
    this.ceiling = ceiling;
  }

  @Override
  public void send(EmailMessage message) {
    // The same ceiling the real adapter enforces, so the stub cannot accept a message the provider
    // would reject - a stub that is more permissive than production teaches the wrong lesson.
    ceiling.enforce(message);
    synchronized (captured) {
      if (captured.size() >= CAPACITY) {
        captured.removeFirst();
      }
      captured.addLast(message);
    }
    log.debug(
        "Stub email seam captured a message for {} (attachment: {})",
        RecipientRedaction.redact(message.to()),
        message.hasAttachment());
  }

  /** Everything captured so far, oldest first. A copy - the caller cannot mutate the buffer. */
  public List<EmailMessage> captured() {
    synchronized (captured) {
      return new ArrayList<>(captured);
    }
  }

  /** Drop everything captured. */
  public void clear() {
    synchronized (captured) {
      captured.clear();
    }
  }
}

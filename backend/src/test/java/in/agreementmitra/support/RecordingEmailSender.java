package in.agreementmitra.support;

import in.agreementmitra.signing.EmailMessage;
import in.agreementmitra.signing.EmailSender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The email seam an integration test drives: records every message and can be told to fail.
 *
 * <p>It replaces the shipped stub (which also sends nothing) only so a test can <b>inject a
 * failure</b> - a hard bounce for one party, an unavailable provider for everyone - without
 * shipping fault-injection hooks in production code. Nothing here reaches a mailbox, a credential,
 * or the network, in either implementation.
 *
 * <p>Thread-safe, because one of the tests completes the same signing request from two threads at
 * once to prove each recipient is still emailed exactly once.
 */
public class RecordingEmailSender implements EmailSender {

  private final List<EmailMessage> sent = Collections.synchronizedList(new ArrayList<>());

  /** Given a message, the failure to raise for it, or {@code null} to let it through. */
  private volatile Function<EmailMessage, RuntimeException> fault = message -> null;

  @Override
  public void send(EmailMessage message) {
    RuntimeException failure = fault.apply(message);
    if (failure != null) {
      throw failure;
    }
    sent.add(message);
  }

  /** Every message the seam accepted, in order. A copy. */
  public List<EmailMessage> sent() {
    synchronized (sent) {
      return new ArrayList<>(sent);
    }
  }

  /** Messages accepted for one recipient address. */
  public List<EmailMessage> sentTo(String recipient) {
    return sent().stream().filter(m -> recipient.equals(m.to())).toList();
  }

  /** Fail every send with {@code failure}. */
  public void failEverything(Function<EmailMessage, RuntimeException> failure) {
    this.fault = failure;
  }

  /** Fail only sends addressed to {@code recipient}. */
  public void failFor(String recipient, Function<EmailMessage, RuntimeException> failure) {
    this.fault = message -> recipient.equals(message.to()) ? failure.apply(message) : null;
  }

  /** Accept everything again. */
  public void healAll() {
    this.fault = message -> null;
  }

  /** Forget every recorded message and accept everything again. */
  public void reset() {
    synchronized (sent) {
      sent.clear();
    }
    healAll();
  }
}

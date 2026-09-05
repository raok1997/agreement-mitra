package in.agreementmitra.signing.contact;

/**
 * Sends one message on one channel. Mirrors {@code EsignProvider} and {@code EmailSender}: the
 * vendor specifics of a channel live behind the interface, so adding a channel is an adapter rather
 * than a change to every caller (design D14).
 *
 * <p><b>Shaped to what email actually needs, and no further.</b> Declaring SMS and WhatsApp without
 * implementing either invites an interface built from guesses about message length, templating, and
 * delivery receipts. The first real second channel should be allowed to reshape this; until then
 * the abstraction stays as small as the one working adapter requires.
 *
 * <p>As with {@code EmailSender}: returning normally means the provider accepted the message, never
 * that it arrived.
 */
public interface ChannelDispatcher {

  /** The channel this adapter serves. One adapter per channel. */
  DeliveryChannel channel();

  /**
   * Hand one message to the provider for this channel.
   *
   * @throws RuntimeException if the provider refused or could not be reached; the adapter's own
   *     exception type classifies the failure
   */
  void send(ChannelMessage message);
}

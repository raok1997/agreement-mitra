package in.agreementmitra.signing.contact;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Routes a message to the adapter for its channel.
 *
 * <p>Refuses a disabled channel even when an adapter exists for it. {@link PartyReachability}
 * should never hand out a destination on a disabled channel in the first place, so reaching that
 * state means a caller built a destination by hand - which is exactly the bug worth failing loudly
 * on rather than quietly sending.
 *
 * <p>Absent adapters are not an error at construction: SMS and WhatsApp are declared channels with
 * no implementation, and a disabled channel with no adapter is the expected steady state.
 */
@Component
public class ChannelDispatch {

  private final Map<DeliveryChannel, ChannelDispatcher> dispatchers =
      new EnumMap<>(DeliveryChannel.class);
  private final DeliveryChannelProperties properties;

  public ChannelDispatch(
      List<ChannelDispatcher> dispatchers, DeliveryChannelProperties properties) {
    this.properties = properties;
    for (ChannelDispatcher dispatcher : dispatchers) {
      this.dispatchers.put(dispatcher.channel(), dispatcher);
    }
  }

  /**
   * Send on the destination's channel.
   *
   * @throws IllegalStateException if the channel is disabled or has no adapter
   */
  public void send(ChannelDestination destination, String subject, String body) {
    send(destination, subject, body, null);
  }

  /**
   * Send on the destination's channel, with a document attached.
   *
   * @throws IllegalStateException if the channel is disabled or has no adapter
   */
  public void send(
      ChannelDestination destination,
      String subject,
      String body,
      ChannelMessage.ChannelAttachment attachment) {
    DeliveryChannel channel = destination.channel();
    if (!properties.isEnabled(channel)) {
      throw new IllegalStateException("delivery channel is not enabled: " + channel);
    }
    ChannelDispatcher dispatcher = dispatchers.get(channel);
    if (dispatcher == null) {
      throw new IllegalStateException("no adapter for delivery channel: " + channel);
    }
    dispatcher.send(new ChannelMessage(destination.destination(), subject, body, attachment));
  }
}

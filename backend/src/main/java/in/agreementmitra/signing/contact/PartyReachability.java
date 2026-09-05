package in.agreementmitra.signing.contact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <b>The</b> definition of "this party can be reached" (design D14). One rule, one place, used by
 * every caller that needs it: the payment gate before an order is created, and the eSign gate
 * before a signing request is raised.
 *
 * <p>Before this existed there were effectively two answers. {@code
 * SigningRequestService.requireContacts} accepted email <b>or</b> mobile, while signed-document
 * delivery is email-only - so a party carrying only a mobile passed the gate, signed, and could
 * never be sent the finished document. Expressing the rule against <b>enabled channels</b> removes
 * that class of disagreement: a channel with no adapter cannot satisfy anything, whatever contact
 * detail happens to be stored for it.
 *
 * <p>Deliberately takes a party's two contact fields rather than an agreement or a signer type. It
 * keeps this class free of any dependency on the agreement model, and keeps it unit-testable with
 * no Spring context.
 */
@Component
@EnableConfigurationProperties(DeliveryChannelProperties.class)
public class PartyReachability {

  /** Same shape the capture DTO validates, so the gate cannot accept what the form rejects. */
  private static final Pattern MOBILE = Pattern.compile("\\+?[0-9]{6,15}");

  /**
   * Deliberately permissive: a local part, an {@code @}, and a dotted domain. Address syntax is
   * already validated at capture; re-implementing RFC 5322 here would only add a second, subtly
   * different opinion about what an address is.
   */
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  private final DeliveryChannelProperties properties;

  public PartyReachability(DeliveryChannelProperties properties) {
    this.properties = properties;
  }

  /** Whether this channel may be used at all. A disabled channel never satisfies reachability. */
  public boolean isEnabled(DeliveryChannel channel) {
    return properties.isEnabled(channel);
  }

  /**
   * A party is reachable when at least one <b>enabled</b> channel has a usable destination for
   * them.
   *
   * @param email the party's email, may be null or blank
   * @param mobile the party's mobile, may be null or blank
   */
  public boolean isReachable(String email, String mobile) {
    return !reachableDestinations(email, mobile).isEmpty();
  }

  /**
   * Every usable route to this party, in channel declaration order. Empty when the party is
   * unreachable - which is the same thing {@link #isReachable} reports, so the two cannot disagree.
   */
  public List<ChannelDestination> reachableDestinations(String email, String mobile) {
    List<ChannelDestination> destinations = new ArrayList<>();
    for (DeliveryChannel channel : DeliveryChannel.values()) {
      if (!isEnabled(channel)) {
        continue;
      }
      destinationFor(channel, email, mobile)
          .ifPresent(destination -> destinations.add(new ChannelDestination(channel, destination)));
    }
    return List.copyOf(destinations);
  }

  /**
   * Which stored field feeds which channel, and whether the stored value is usable. The single
   * point at which a party's fields and the channels that consume them are related - so adding a
   * channel is an edit here rather than a search for every place that assumed email.
   *
   * <p>Does <b>not</b> consider enablement: callers reach it through {@link
   * #reachableDestinations}, which does.
   */
  private static Optional<String> destinationFor(
      DeliveryChannel channel, String email, String mobile) {
    return switch (channel) {
      case EMAIL -> usable(email, EMAIL);
      case SMS, WHATSAPP -> usable(mobile, MOBILE);
    };
  }

  private static Optional<String> usable(String value, Pattern shape) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() || !shape.matcher(trimmed).matches()
        ? Optional.empty()
        : Optional.of(trimmed);
  }
}

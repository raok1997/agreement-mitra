package in.agreementmitra.signing.contact;

/**
 * One usable way to reach one party: an enabled channel plus the destination on it.
 *
 * <p>Produced only by {@link PartyReachability}, which is the single place that decides whether a
 * channel is enabled and which stored field feeds it. A {@code ChannelDestination} therefore always
 * represents a route that is actually usable - callers do not re-check enablement.
 *
 * @param channel the channel to dispatch on
 * @param destination the address or number on that channel
 */
public record ChannelDestination(DeliveryChannel channel, String destination) {

  /**
   * Never renders the destination - an accidental {@code log.info("{}", destination)} must not leak
   * a party's address or number. Use {@code RecipientRedaction} when a recipient must be logged.
   */
  @Override
  public String toString() {
    return "ChannelDestination{channel=" + channel + "}";
  }
}

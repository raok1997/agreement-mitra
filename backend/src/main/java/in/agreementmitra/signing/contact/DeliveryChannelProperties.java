package in.agreementmitra.signing.contact;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-channel enablement, from {@code delivery.channels.*.enabled}, plus the public base URL that
 * outbound links are built from.
 *
 * <p><b>Fail closed.</b> A channel absent from configuration is disabled, not enabled. A deployment
 * that half-configures a channel therefore refuses to use it rather than dispatching somewhere
 * unintended - the same posture the mail provider takes by defaulting to the stub.
 *
 * @param publicBaseUrl where the customer-facing app lives; blank means no link can be built, so
 *     nothing that needs one is sent
 * @param channels enablement per channel; absent entries mean disabled
 */
@ConfigurationProperties(prefix = "delivery")
public record DeliveryChannelProperties(
    String publicBaseUrl, Map<DeliveryChannel, ChannelSettings> channels) {

  public DeliveryChannelProperties {
    channels = channels == null ? Map.of() : new EnumMap<>(channels);
  }

  /** True when a link can actually be built. Nothing that needs one may be sent otherwise. */
  public boolean hasPublicBaseUrl() {
    return publicBaseUrl != null && !publicBaseUrl.isBlank();
  }

  /** True only when the channel is present in configuration AND enabled. */
  public boolean isEnabled(DeliveryChannel channel) {
    ChannelSettings settings = channels.get(channel);
    return settings != null && settings.enabled();
  }

  /**
   * @param enabled whether this channel may be used; defaults to false when unset
   */
  public record ChannelSettings(boolean enabled) {}
}

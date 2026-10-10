package in.agreementmitra.signing.staffalert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Staff alert configuration ({@code staff-alert.*}). The sweep's schedule and its on/off switch are
 * read as placeholders by {@link StaffAlertDispatchJob}; retry bounds and timeouts are code
 * constants. What is left is the channel secret.
 *
 * <p>The webhook URL is bound as a plain {@code String} with no Bean Validation: a bind or
 * validation failure would print the rejected value, and the value is a secret. Both {@code
 * toString()}s mask it.
 */
@ConfigurationProperties(prefix = "staff-alert")
record StaffAlertProperties(Discord discord) {

  StaffAlertProperties {
    if (discord == null) {
      discord = new Discord("");
    }
  }

  @Override
  public String toString() {
    return "StaffAlertProperties{discord=" + discord + "}";
  }

  /**
   * @param webhookUrl the Discord incoming-webhook URL; blank means staff alerts are off
   */
  record Discord(String webhookUrl) {

    Discord {
      webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @Override
    public String toString() {
      return "Discord{webhookUrl=" + (webhookUrl.isEmpty() ? "<blank>" : "<set>") + "}";
    }
  }
}

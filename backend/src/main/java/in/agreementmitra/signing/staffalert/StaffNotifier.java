package in.agreementmitra.signing.staffalert;

/**
 * The channel staff alerts go out on. One implementation today ({@link DiscordStaffNotifier}); a
 * WhatsApp adapter replaces it behind this seam.
 */
interface StaffNotifier {

  /** Whether a usable channel is configured. {@code false} switches staff alerts off entirely. */
  boolean configured();

  /**
   * Post one alert.
   *
   * @throws StaffAlertDeliveryException when the channel did not accept it, classified transient or
   *     permanent
   */
  void send(StaffAlertMessage message);
}

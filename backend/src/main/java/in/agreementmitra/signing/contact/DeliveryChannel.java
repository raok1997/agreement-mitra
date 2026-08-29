package in.agreementmitra.signing.contact;

/**
 * A route by which a party can be reached. Modelled as a first-class concept rather than left
 * implicit in "has an email address" so that the reachability rule (see {@link PartyReachability})
 * is written once and does not need revisiting when a channel is switched on (design D14).
 *
 * <p><b>Only {@link #EMAIL} is enabled today.</b> {@link #SMS} and {@link #WHATSAPP} are declared
 * because the contact details that would feed them are already collected, and because the rule that
 * depends on them should not have to change shape later. Neither has an adapter: no provider is
 * integrated, and {@code mobile-otp-auth} is parked. A declared-but-disabled channel never
 * satisfies reachability and must never be presented to a customer as a way they will receive
 * anything.
 *
 * <p>Which contact detail feeds which channel is deliberately kept in one place - {@link
 * PartyReachability#destinationFor} - so a party's stored fields and the channels that can use them
 * cannot drift apart.
 */
public enum DeliveryChannel {

  /** Enabled. Backed by the existing outbound email seam. */
  EMAIL,

  /** Declared, disabled, no adapter. Would use the party's mobile number. */
  SMS,

  /** Declared, disabled, no adapter. Would use the party's mobile number. */
  WHATSAPP
}

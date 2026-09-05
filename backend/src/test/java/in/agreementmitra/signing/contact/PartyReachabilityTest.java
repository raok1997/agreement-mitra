package in.agreementmitra.signing.contact;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.contact.DeliveryChannelProperties.ChannelSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The single reachability rule. No Spring context, no I/O.
 *
 * <p>What these pin is the property the whole change rests on: <b>a channel with no adapter cannot
 * satisfy reachability, whatever contact detail is stored for it</b>. Before this rule existed, the
 * eSign gate accepted email <b>or</b> mobile while delivery was email-only, so a party carrying
 * just a mobile passed the gate, signed, and could never be sent the finished agreement. Expressing
 * reachability against enabled channels is what makes that class of disagreement impossible, so it
 * is what these tests are about.
 */
class PartyReachabilityTest {

  private static PartyReachability with(Map<DeliveryChannel, Boolean> enablement) {
    Map<DeliveryChannel, ChannelSettings> channels =
        enablement.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, e -> new ChannelSettings(e.getValue())));
    return new PartyReachability(new DeliveryChannelProperties("http://localhost:5173", channels));
  }

  /** The shipped configuration: email on, SMS and WhatsApp declared but off. */
  private static PartyReachability shipped() {
    return with(
        Map.of(
            DeliveryChannel.EMAIL, true,
            DeliveryChannel.SMS, false,
            DeliveryChannel.WHATSAPP, false));
  }

  @Test
  void anEmailAddressReachesAPartyWhenEmailIsEnabled() {
    assertThat(shipped().isReachable("asha@example.com", null)).isTrue();
  }

  @Test
  void aMobileAloneDoesNotReachAPartyWhileSmsIsDisabled() {
    // The exact case the old email-OR-mobile rule admitted: this party could sign and could never
    // be sent the signed agreement. Admitting them here would reintroduce that bug.
    assertThat(shipped().isReachable(null, "9000000001")).isFalse();
  }

  @Test
  void aDisabledChannelNeverSatisfiesReachabilityHoweverGoodTheContact() {
    PartyReachability everythingOff =
        with(
            Map.of(
                DeliveryChannel.EMAIL, false,
                DeliveryChannel.SMS, false,
                DeliveryChannel.WHATSAPP, false));
    assertThat(everythingOff.isReachable("asha@example.com", "9000000001")).isFalse();
  }

  @Test
  void enablingSmsMakesAMobileSufficientWithoutAnyRuleChange() {
    // The point of modelling channels: switching one on is configuration, not a new rule.
    PartyReachability smsOn = with(Map.of(DeliveryChannel.EMAIL, true, DeliveryChannel.SMS, true));
    assertThat(smsOn.isReachable(null, "9000000001")).isTrue();
  }

  @Test
  void aChannelAbsentFromConfigurationIsDisabledNotEnabled() {
    // Fail closed: a half-configured deployment must refuse a channel, not assume it.
    PartyReachability onlyEmailMentioned = with(Map.of(DeliveryChannel.EMAIL, true));
    assertThat(onlyEmailMentioned.isReachable(null, "9000000001")).isFalse();
  }

  @Test
  void blankAndMalformedContactsDoNotReachAnyone() {
    PartyReachability reachability = shipped();
    assertThat(reachability.isReachable(null, null)).isFalse();
    assertThat(reachability.isReachable("   ", "  ")).isFalse();
    assertThat(reachability.isReachable("not-an-address", null)).isFalse();
    assertThat(reachability.isReachable("missing@domain", null)).isFalse();
  }

  @Test
  void surroundingWhitespaceIsToleratedRatherThanFailingAParty() {
    assertThat(shipped().isReachable("  asha@example.com  ", null)).isTrue();
  }

  @Test
  void destinationsCarryTheChannelTheyBelongTo() {
    PartyReachability bothOn = with(Map.of(DeliveryChannel.EMAIL, true, DeliveryChannel.SMS, true));

    assertThat(bothOn.reachableDestinations("asha@example.com", "9000000001"))
        .extracting(ChannelDestination::channel)
        .containsExactly(DeliveryChannel.EMAIL, DeliveryChannel.SMS);
  }

  @Test
  void destinationsAreEmptyForAnUnreachableParty() {
    assertThat(shipped().reachableDestinations(null, "9000000001")).isEmpty();
  }

  @Test
  void aDestinationNeverRendersItsContactValue() {
    // An accidental log of a destination must not leak a party's address.
    ChannelDestination destination =
        new ChannelDestination(DeliveryChannel.EMAIL, "asha@example.com");
    assertThat(destination.toString()).doesNotContain("asha@example.com");
  }
}

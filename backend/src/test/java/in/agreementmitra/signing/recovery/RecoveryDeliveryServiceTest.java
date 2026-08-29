package in.agreementmitra.signing.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.contact.ChannelDispatch;
import in.agreementmitra.signing.contact.ChannelDispatcher;
import in.agreementmitra.signing.contact.ChannelMessage;
import in.agreementmitra.signing.contact.DeliveryChannel;
import in.agreementmitra.signing.contact.DeliveryChannelProperties;
import in.agreementmitra.signing.contact.DeliveryChannelProperties.ChannelSettings;
import in.agreementmitra.signing.contact.PartyReachability;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who the recovery link goes to, and what it may say.
 *
 * <p>Two properties matter more than the rest and both are asserted here rather than reviewed:
 *
 * <ul>
 *   <li><b>Every party, not just the payer</b> (design D15) - a payer-only rule would strand the
 *       agreement whenever that one person went quiet, which is the trap this change removes.
 *   <li><b>The message carries no agreement content</b> (design D7) - it is a permanent credential
 *       sitting in a mailbox, so a forwarded copy must not itself disclose the parties, the
 *       property, or the money.
 * </ul>
 */
class RecoveryDeliveryServiceTest {

  private static final UUID AGREEMENT_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
  private static final String REFERENCE = "AM3G3VXSAKD";

  /** Captures what was dispatched instead of sending it. */
  private static final class RecordingDispatcher implements ChannelDispatcher {
    private final List<ChannelMessage> sent = new ArrayList<>();
    private final String failFor;

    RecordingDispatcher(String failFor) {
      this.failFor = failFor;
    }

    @Override
    public DeliveryChannel channel() {
      return DeliveryChannel.EMAIL;
    }

    @Override
    public void send(ChannelMessage message) {
      if (message.destination().equals(failFor)) {
        throw new IllegalStateException("provider refused");
      }
      sent.add(message);
    }
  }

  private static AgreementResponse agreement(String ownerEmail, String tenantEmail) {
    return new AgreementResponse(
        AGREEMENT_ID,
        REFERENCE,
        "12 Test Street, Indiranagar, Bengaluru 560038",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        LocalDate.of(2026, 9, 1),
        LocalDate.of(2027, 7, 31),
        10,
        Instant.now(),
        List.of(
            new SignerResponse(
                UUID.randomUUID(),
                "Asha Owner",
                "Asha",
                "Owner",
                "Ravi Owner",
                "1 A St",
                ownerEmail,
                null,
                Role.OWNER),
            new SignerResponse(
                UUID.randomUUID(),
                "Tara Tenant",
                "Tara",
                "Tenant",
                "Hari Tenant",
                "3 C St",
                tenantEmail,
                null,
                Role.TENANT)));
  }

  private static RecoveryDeliveryService service(
      RecordingDispatcher dispatcher, String publicBaseUrl) {
    DeliveryChannelProperties properties =
        new DeliveryChannelProperties(
            publicBaseUrl, Map.of(DeliveryChannel.EMAIL, new ChannelSettings(true)));
    return new RecoveryDeliveryService(
        new PartyReachability(properties),
        new ChannelDispatch(List.of(dispatcher), properties),
        properties);
  }

  @Test
  void sendsToEveryPartyNotJustOne() {
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    int sent =
        service(dispatcher, "http://localhost:5173")
            .sendRecoveryLink(agreement("asha@example.com", "tara@example.com"));

    assertThat(sent).isEqualTo(2);
    assertThat(dispatcher.sent)
        .extracting(ChannelMessage::destination)
        .containsExactlyInAnyOrder("asha@example.com", "tara@example.com");
  }

  @Test
  void oneRecipientFailingDoesNotDenyTheOther() {
    // A tenant's provider refusing must not cost the owner their link, and vice versa.
    RecordingDispatcher dispatcher = new RecordingDispatcher("asha@example.com");

    int sent =
        service(dispatcher, "http://localhost:5173")
            .sendRecoveryLink(agreement("asha@example.com", "tara@example.com"));

    assertThat(sent).isEqualTo(1);
    assertThat(dispatcher.sent)
        .extracting(ChannelMessage::destination)
        .containsExactly("tara@example.com");
  }

  @Test
  void anUnreachablePartyIsSkippedWithoutStoppingTheRest() {
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    int sent =
        service(dispatcher, "http://localhost:5173")
            .sendRecoveryLink(agreement(null, "tara@example.com"));

    assertThat(sent).isEqualTo(1);
  }

  @Test
  void sendsNothingWithoutAPublicBaseUrl() {
    // Fail closed: no base URL means no link can be built, so no half-formed message goes out.
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    int sent =
        service(dispatcher, "  ").sendRecoveryLink(agreement("asha@example.com", "t@example.com"));

    assertThat(sent).isZero();
    assertThat(dispatcher.sent).isEmpty();
  }

  @Test
  void theMessageCarriesTheReferenceAndALinkToTheAgreement() {
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    service(dispatcher, "http://localhost:5173")
        .sendRecoveryLink(agreement("asha@example.com", null));

    ChannelMessage message = dispatcher.sent.get(0);
    assertThat(message.subject()).contains(REFERENCE);
    assertThat(message.body()).contains(REFERENCE);
    assertThat(message.body()).contains("http://localhost:5173/agreement/" + AGREEMENT_ID);
  }

  @Test
  void theMessageDisclosesNoAgreementContent() {
    // The core of design D7. This link does not expire, so a forwarded or archived copy is a live
    // credential - it must not ALSO be a disclosure of who the parties are or what they agreed.
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    service(dispatcher, "http://localhost:5173")
        .sendRecoveryLink(agreement("asha@example.com", "tara@example.com"));

    ChannelMessage message = dispatcher.sent.get(0);
    assertThat(message.body())
        .doesNotContain("Asha")
        .doesNotContain("Tara")
        .doesNotContain("Indiranagar")
        .doesNotContain("25000")
        .doesNotContain("50000");
    assertThat(message.subject()).doesNotContain("Asha").doesNotContain("Indiranagar");
  }

  @Test
  void theMessageNeverCarriesAnAttachment() {
    // The draft message attaches the agreement; this one must never converge on that. A permanent
    // credential in a mailbox must not also be a copy of the document.
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    service(dispatcher, "http://localhost:5173")
        .sendRecoveryLink(agreement("asha@example.com", null));

    assertThat(dispatcher.sent.get(0).hasAttachment()).isFalse();
  }

  @Test
  void theMessageTellsTheCustomerHowAccessEnds() {
    // The link is permanent and claiming is the only revocation, so the customer has to be told -
    // otherwise the off-switch is an accident of the access model rather than a feature.
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    service(dispatcher, "http://localhost:5173")
        .sendRecoveryLink(agreement("asha@example.com", null));

    assertThat(dispatcher.sent.get(0).body()).containsIgnoringCase("sign in");
  }

  @Test
  void theLinkComesFromConfigurationAndToleratesATrailingSlash() {
    RecordingDispatcher dispatcher = new RecordingDispatcher(null);

    service(dispatcher, "https://agreementmitra.example/")
        .sendRecoveryLink(agreement("asha@example.com", null));

    assertThat(dispatcher.sent.get(0).body())
        .contains("https://agreementmitra.example/agreement/" + AGREEMENT_ID)
        .doesNotContain("example//agreement");
  }

  @Test
  void aChannelMessageNeverRendersItsDestinationOrBody() {
    ChannelMessage message = new ChannelMessage("asha@example.com", "subject", "secret body");
    assertThat(message.toString()).doesNotContain("asha@example.com").doesNotContain("secret body");
  }
}

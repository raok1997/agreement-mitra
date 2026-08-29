package in.agreementmitra.signing.recovery;

import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.contact.ChannelDestination;
import in.agreementmitra.signing.contact.ChannelDispatch;
import in.agreementmitra.signing.contact.DeliveryChannelProperties;
import in.agreementmitra.signing.contact.PartyReachability;
import in.agreementmitra.signing.mail.RecipientRedaction;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends the recovery link to <b>every</b> party on an agreement.
 *
 * <p><b>Every party, not just the payer</b> (design D15). The transaction is complete once payment
 * is received, and which party pushes the agreement over the line is not something this system
 * needs an opinion about. Restricting delivery to whoever paid would mean treating "who paid" as an
 * authorisation input and stranding the agreement whenever that one person became unavailable -
 * which is the trap this change exists to remove, merely narrowed to one person.
 *
 * <p><b>The link is the credential and it does not expire.</b> It carries the agreement identifier,
 * which is the bearer capability the customer already held in their browser before they closed the
 * tab. It stops working when the agreement is claimed into an account, because anonymous access to
 * a claimed agreement is refused - that is the revocation, and the message says so.
 *
 * <p><b>Never throws.</b> Callers are payment confirmation and the recovery endpoint; neither may
 * fail because a mail server did. Recipients are attempted independently so one party's failure
 * does not deny the other.
 */
@Service
public class RecoveryDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(RecoveryDeliveryService.class);

  private final PartyReachability reachability;
  private final ChannelDispatch dispatch;
  private final DeliveryChannelProperties properties;

  RecoveryDeliveryService(
      PartyReachability reachability,
      ChannelDispatch dispatch,
      DeliveryChannelProperties properties) {
    this.reachability = reachability;
    this.dispatch = dispatch;
    this.properties = properties;
  }

  /**
   * Send the recovery link to every reachable party.
   *
   * <p>Fails closed and silently: with no public base URL configured there is no link to send, so
   * nothing is attempted. The caller's response never varies on any of this (design D1) - the only
   * signal is operator-facing.
   *
   * @return how many recipients were dispatched to; zero is a normal outcome, not an error
   */
  public int sendRecoveryLink(AgreementResponse agreement) {
    if (!properties.hasPublicBaseUrl()) {
      log.warn("Recovery link not sent: no public base URL is configured");
      return 0;
    }

    String reference = agreement.trackingNumber();
    String link = recoveryLink(agreement);
    String subject = RecoveryMessages.subject(reference);
    String body = RecoveryMessages.body(reference, link);

    int sent = 0;
    for (AgreementResponse.SignerResponse signer : agreement.signers()) {
      List<ChannelDestination> destinations =
          reachability.reachableDestinations(signer.email(), signer.mobile());
      for (ChannelDestination destination : destinations) {
        try {
          // Body only. This message must never carry the agreement as an attachment - see
          // RecoveryMessages for why the two outbound messages are deliberately different.
          dispatch.send(destination, subject, body);
          sent++;
        } catch (RuntimeException failed) {
          log.warn(
              "Recovery link delivery failed for one recipient on {} ({})",
              destination.channel(),
              RecipientRedaction.redact(destination.destination()));
        }
      }
    }
    return sent;
  }

  /**
   * The link the parties receive. Built from configuration, never from a request header - a spoofed
   * {@code Host} would otherwise rewrite where an outbound email sends the customer.
   */
  private String recoveryLink(AgreementResponse agreement) {
    String base = properties.publicBaseUrl().trim();
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return trimmed + "/agreement/" + agreement.id();
  }
}

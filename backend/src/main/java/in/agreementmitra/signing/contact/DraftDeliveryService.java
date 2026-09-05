package in.agreementmitra.signing.contact;

import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.mail.RecipientRedaction;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends the current draft agreement to every party, once contacts are confirmed and before payment
 * begins (design D17).
 *
 * <p><b>Why here and not after payment.</b> Each party should see what they are about to be asked
 * to sign, and the moment their address is first known to be good is the moment to prove it works -
 * before anything downstream depends on it.
 *
 * <p><b>This does not weaken the payment gate.</b> The draft is already downloadable from the
 * capture screen before payment, so sending it discloses nothing a customer could not already take.
 * What the gate protects is fulfilment - the e-stamp and the signatures - and none of that moves
 * here.
 *
 * <p><b>Never throws.</b> A party who does not receive the draft still receives the agreement
 * through the signing invitation and the signed-document delivery that follow, so a mail failure
 * must not stand between a customer and paying. Failures are logged per recipient and the rest of
 * the parties are still attempted.
 */
@Service
public class DraftDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(DraftDeliveryService.class);

  private static final String SUBJECT_PREFIX = "Draft rental agreement for your review - ";

  private final PartyReachability reachability;
  private final ChannelDispatch dispatch;
  private final AgreementService agreementService;
  private final BlobStore blobStore;

  DraftDeliveryService(
      PartyReachability reachability,
      ChannelDispatch dispatch,
      AgreementService agreementService,
      BlobStore blobStore) {
    this.reachability = reachability;
    this.dispatch = dispatch;
    this.agreementService = agreementService;
    this.blobStore = blobStore;
  }

  /**
   * Load the stored draft and send it to every party. Swallows every failure, including a missing
   * or unreadable blob: this runs after contacts have already been saved, and losing the save
   * because a mail server was unreachable would be a worse outcome than a party not getting a copy.
   *
   * <p>Called outside the transaction that saved the contacts - deliberately. Holding a database
   * transaction open across a mail round-trip ties a row lock to a third party's availability.
   */
  public void sendStoredDraftToParties(AgreementResponse agreement) {
    try {
      byte[] draft = agreementService.draftPdfKey(agreement.id()).map(blobStore::get).orElse(null);
      sendDraftToParties(agreement, agreement.trackingNumber(), draft);
    } catch (RuntimeException failed) {
      log.warn("Draft delivery could not be attempted for the agreement");
    }
  }

  /**
   * Send the draft to every reachable party. Returns the number of parties successfully dispatched
   * to, so a caller can record the outcome without needing to interpret exceptions.
   *
   * @param agreement the agreement whose parties are being told
   * @param reference the tracking reference, shown so the customer can quote it
   * @param draftPdf the rendered draft, or null when none is stored yet - in which case nothing is
   *     sent, because a message announcing an absent document helps nobody
   */
  public int sendDraftToParties(AgreementResponse agreement, String reference, byte[] draftPdf) {
    if (draftPdf == null || draftPdf.length == 0) {
      log.warn("Draft delivery skipped: no stored draft for the agreement");
      return 0;
    }

    ChannelMessage.ChannelAttachment attachment =
        new ChannelMessage.ChannelAttachment(
            "rental-agreement-" + reference + ".pdf", "application/pdf", draftPdf);

    int sent = 0;
    for (AgreementResponse.SignerResponse signer : agreement.signers()) {
      List<ChannelDestination> destinations =
          reachability.reachableDestinations(signer.email(), signer.mobile());
      for (ChannelDestination destination : destinations) {
        // Each recipient is attempted independently: one party's provider failure must not stop
        // the other party being told about their own agreement.
        try {
          // Addressed to THIS party by name: the same draft goes to every signer, but a letter
          // about someone's own tenancy should not read like a circular.
          dispatch.send(
              destination, SUBJECT_PREFIX + reference, body(reference, signer.name()), attachment);
          sent++;
        } catch (RuntimeException failed) {
          // Redacted recipient only, and no exception message that might carry an address.
          log.warn(
              "Draft delivery failed for one recipient on {} ({})",
              destination.channel(),
              RecipientRedaction.redact(destination.destination()));
        }
      }
    }
    return sent;
  }

  /**
   * The covering letter for the attached draft.
   *
   * <p><b>Formal, and still deliberately thin on content.</b> This accompanies a legal instrument,
   * so it reads as correspondence rather than as a notification - but it remains the covering
   * letter, not a summary. It does not restate the rent, the dates or the parties: those live in
   * the attachment, and stating them twice creates two versions that can disagree.
   *
   * <p><b>Every claim here is one the system can stand behind.</b> The document really is unstamped
   * and unsigned at this point; the terms really do freeze when the order is placed at payment; and
   * a reply really does reach a monitored mailbox. Nothing promises a channel that is not enabled -
   * email is the only route described, because it is the only one that runs.
   *
   * @param reference the tracking reference, so the customer can quote it
   * @param partyName the recipient's name as captured; blank falls back to a neutral salutation
   */
  private static String body(String reference, String partyName) {
    String salutation =
        partyName == null || partyName.isBlank() ? "Dear Sir or Madam," : "Dear " + partyName + ",";
    return """
        %s

        Please find attached the draft rental agreement prepared for your review.

        Reference: %s

        We request that you read the draft in full and satisfy yourself that the particulars \
        recorded in it are correct. Should any detail require amendment, kindly return to your \
        agreement and make the correction before proceeding to payment, as the terms are fixed \
        once the order is placed.

        Please note that this document is a draft. It has not been stamped and it has not been \
        signed, and it takes effect only once every party has signed it electronically.

        Should you have any questions, you are welcome to reply to this email.

        Yours sincerely,
        AgreementMitra
        """
        .formatted(salutation, reference);
  }
}

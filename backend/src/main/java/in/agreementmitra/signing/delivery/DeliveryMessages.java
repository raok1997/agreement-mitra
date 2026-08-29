package in.agreementmitra.signing.delivery;

import in.agreementmitra.signing.EmailAttachment;
import in.agreementmitra.signing.EmailMessage;

/**
 * Composes the two messages this capability can send: the signed agreement as an attachment, and -
 * when the document exceeds the attachment ceiling - a notification pointing at the durable in-app
 * copy instead (design D6).
 *
 * <p><b>The audit trail is never composed into either.</b> It carries eKYC-derived detail (auth
 * mode, timestamps, name-match data) that should not be distributed into mailboxes; it is retained
 * and produced on request or in a dispute. There is deliberately no method here that could attach
 * it.
 *
 * <p>Bodies carry the agreement's own tracking reference and nothing else identifying - no party
 * names, no property address, no financial terms. Everything sensitive is inside the attachment,
 * which goes only to a signing-verified address.
 */
final class DeliveryMessages {

  /** What the recipient sees the attachment called. Never a storage key. */
  static final String ATTACHMENT_FILENAME = "signed-rental-agreement.pdf";

  static final String CONTENT_TYPE_PDF = "application/pdf";

  private static final String SUBJECT = "Your signed rental agreement";

  private DeliveryMessages() {}

  /** The signed agreement itself, attached. */
  static EmailMessage withAttachment(String recipient, String reference, byte[] signedPdf) {
    String body =
        "Your rental agreement has been signed by all parties.\n\n"
            + "The signed agreement is attached to this email, and a copy remains available in "
            + "your AgreementMitra account.\n\n"
            + "Reference: "
            + reference
            + "\n\n"
            + "Please keep this email: the attached PDF is your copy of the executed agreement.\n";
    return new EmailMessage(
        recipient,
        SUBJECT,
        body,
        new EmailAttachment(ATTACHMENT_FILENAME, CONTENT_TYPE_PDF, signedPdf));
  }

  /**
   * The oversize fallback: no attachment, a pointer at the in-app copy. Never a truncated or
   * partial attachment - a document that arrives incomplete is worse than one that does not arrive,
   * because it looks like it worked.
   */
  static EmailMessage notificationOnly(String recipient, String reference) {
    String body =
        "Your rental agreement has been signed by all parties.\n\n"
            + "The signed agreement is too large to send as an email attachment, so it is "
            + "available to download from your AgreementMitra account.\n\n"
            + "Reference: "
            + reference
            + "\n";
    return new EmailMessage(recipient, SUBJECT, body);
  }
}

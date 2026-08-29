package in.agreementmitra.signing;

/**
 * One outbound message crossing the vendor-neutral {@link EmailSender} seam: recipient, subject,
 * plain-text body, and an optional attachment. <b>No provider type crosses this seam</b> - the
 * calling code never sees a MIME message, a transport, or a Zoho/ZeptoMail concept, so swapping the
 * adapter is a configuration change rather than a rewrite.
 *
 * @param to the single recipient address; for the signed agreement this is always a
 *     <b>signing-verified</b> address (design D2), never a draft-time one
 * @param subject the message subject
 * @param body the plain-text body
 * @param attachment the optional attachment, or {@code null} for a body-only notification (the
 *     oversize fallback of design D6)
 */
public record EmailMessage(String to, String subject, String body, EmailAttachment attachment) {

  /** A body-only message: no attachment. */
  public EmailMessage(String to, String subject, String body) {
    this(to, subject, body, null);
  }

  public boolean hasAttachment() {
    return attachment != null;
  }

  /**
   * Never renders the recipient address, the body, or the attachment bytes - the whole point is
   * that an accidental {@code log.info("{}", message)} leaks nothing.
   */
  @Override
  public String toString() {
    return "EmailMessage{subject=" + subject + ", attachment=" + hasAttachment() + "}";
  }
}

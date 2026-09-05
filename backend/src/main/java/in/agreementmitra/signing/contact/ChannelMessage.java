package in.agreementmitra.signing.contact;

/**
 * One message crossing the {@link ChannelDispatcher} seam. No provider type crosses it - the
 * calling code never sees a MIME message, an SMS segment, or a WhatsApp template id.
 *
 * <p><b>The attachment is optional and most messages must not carry one.</b> Two messages travel
 * this seam and they are deliberately different: the pre-payment draft carries the agreement,
 * because its whole purpose is that the parties read it; the recovery link carries a reference and
 * a link and nothing else, because it is a permanent credential sitting in a mailbox (design D7).
 * Giving the recovery message an attachment is a mistake, not a convenience.
 *
 * @param destination where to send it, taken from a {@link ChannelDestination} rather than supplied
 *     by a caller, so a request body can never redirect a message
 * @param subject the subject; channels without a subject concept ignore it
 * @param body the plain-text body
 * @param attachment the document to attach, or null for a body-only message
 */
public record ChannelMessage(
    String destination, String subject, String body, ChannelAttachment attachment) {

  /** A body-only message. The correct constructor for anything that is not sending a document. */
  public ChannelMessage(String destination, String subject, String body) {
    this(destination, subject, body, null);
  }

  public boolean hasAttachment() {
    return attachment != null;
  }

  /**
   * Never renders the destination or the body, so an accidental log of a whole message leaks
   * neither a party's address nor the content sent to them.
   */
  @Override
  public String toString() {
    return "ChannelMessage{subject=" + subject + ", attachment=" + hasAttachment() + "}";
  }

  /**
   * A document on a {@link ChannelMessage}. Bytes are never rendered.
   *
   * @param filename the name the recipient sees, never a storage key
   * @param contentType the MIME type of the content
   * @param content the raw bytes
   */
  public record ChannelAttachment(String filename, String contentType, byte[] content) {

    /** Filename and size only. Never the bytes. */
    @Override
    public String toString() {
      return "ChannelAttachment{filename="
          + filename
          + ", bytes="
          + (content == null ? 0 : content.length)
          + "}";
    }
  }
}

package in.agreementmitra.documents.api;

/**
 * The result of a generate projection: the rendered {@code pdf} bytes plus the {@link
 * EffectiveTemplateIdentity} of the template they were generated from. The identity is the anchor
 * CR-3's pin records; in this change the bytes are stored as the draft and the identity is not yet
 * consumed.
 *
 * <p>{@code executionDate} is the ISO execution date the document printed: the submitted {@code
 * agreementDate}, or the date of the render when that was blank. A caller that must re-render the
 * same document later passes it back as {@code agreementDate}, so the date does not move. It may be
 * {@code null} from a caller that does not report it.
 *
 * <p>The PDF bytes carry party PII and are never logged.
 */
public record DocumentProjectionResult(
    byte[] pdf, EffectiveTemplateIdentity identity, String executionDate) {

  /** A result that does not report the execution date it printed. */
  public DocumentProjectionResult(byte[] pdf, EffectiveTemplateIdentity identity) {
    this(pdf, identity, null);
  }
}

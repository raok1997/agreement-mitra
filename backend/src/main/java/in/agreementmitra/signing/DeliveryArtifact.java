package in.agreementmitra.signing;

/**
 * Which artifact a delivery record delivers.
 *
 * <p>Only {@link #SIGNED_AGREEMENT} is ever delivered. The <b>audit trail is never emailed</b>: it
 * carries eKYC-derived detail (auth mode, timestamps, name-match data) that does not belong in
 * mailboxes. It is retained and produced on request or in a dispute. This enum has one member on
 * purpose - so "which artifact" is a value rather than an assumption - and adding a second member
 * would be a deliberate decision, not a slip.
 */
public enum DeliveryArtifact {
  SIGNED_AGREEMENT
}

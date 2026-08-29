package in.agreementmitra.signing.agreement;

/**
 * One party as the staff stamp-fulfilment queue is allowed to see them: their role, their full name
 * as it will appear on the instrument, and their father's name.
 *
 * <p>This is <b>personal data</b> and exists for exactly one reason: buying an e-stamp certificate
 * requires naming the first party and the second party on the vendor's form. An operator who cannot
 * see the names cannot do the job the queue exists to support. It carries nothing beyond those two
 * names - no contact details, no address, no identity-document data of any kind.
 *
 * <p>{@code name} is the full name as per Aadhaar (the same value handed to the eSign provider), so
 * what an operator transcribes onto the certificate matches what will be signed. It is deliberately
 * not a re-join of the first/last capture fields, which would drift from an overridden name.
 *
 * <p>{@link #toString()} is overridden to emit the role only. A record's generated {@code toString}
 * prints every component, so the default would leak both names into any log line that interpolates
 * one -- see {@code Signer.toString()}, which sets the same precedent.
 */
public record StaffPartyView(Role role, String name, String fatherName) {

  /** Role only -- never a name. Module-wide DEBUG must not leak party PII. */
  @Override
  public String toString() {
    return "StaffPartyView{role=" + role + "}";
  }
}

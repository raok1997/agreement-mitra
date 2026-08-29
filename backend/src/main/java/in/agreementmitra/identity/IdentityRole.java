package in.agreementmitra.identity;

/**
 * What an account is allowed to be, as a server-managed property of the identity record (design
 * D7). Deliberately NOT derived from any identity-provider claim and never settable from a request
 * body, header, or signup form -- an IdP's user-editable profile fields must never become an
 * authorization surface.
 *
 * <p>{@link #CUSTOMER} is the default for every self-service signup. {@link #STAFF} is an
 * AgreementMitra operator and is granted only by a deliberate out-of-band database action.
 */
public enum IdentityRole {

  /** A self-service user. The default for every newly provisioned account. */
  CUSTOMER,

  /**
   * An AgreementMitra operator. Granted out-of-band; the only role allowed to upload an e-stamp.
   */
  STAFF
}

package in.agreementmitra.signing;

import java.util.List;

/**
 * Handle returned after creating a signing request with a provider. Multi-invitee: carries the
 * vendor document id plus one signing URL per invitee.
 *
 * @param providerDocumentId the vendor's document id for this request (store it)
 * @param invitees one entry per invitee, in the same order as the request
 * @param webhookKey the per-transaction webhook credential the vendor issued for this transaction,
 *     or {@code null} when the vendor authenticates its callback some other way (e.g. a body MAC
 *     under a config-wide secret). It is carried out through this value object - rather than the
 *     adapter storing it - so persistence stays outside the vendor boundary; the module encrypts it
 *     before it reaches the database, never logs it, and compares it in constant time (design D3).
 */
public record SignSession(
    String providerDocumentId, List<InviteeSession> invitees, String webhookKey) {

  /** For providers that issue no per-transaction webhook credential. */
  public SignSession(String providerDocumentId, List<InviteeSession> invitees) {
    this(providerDocumentId, invitees, null);
  }

  /**
   * The per-invitee result of creating a signing request.
   *
   * @param email the invitee this URL belongs to (used to map back to the canonical signer)
   * @param signUrl the URL to show that signer — a bearer capability; never log it
   * @param expiryDate when the signing URL expires (vendor-formatted string)
   * @param providerInviteeId the provider's per-invitee identifier (persisted for later
   *     status-correlation), or {@code null} if the provider does not expose one
   */
  public record InviteeSession(
      String email, String signUrl, String expiryDate, String providerInviteeId) {}

  /** Never renders the webhook key - it is a credential. */
  @Override
  public String toString() {
    return "SignSession{invitees=" + invitees.size() + "}";
  }
}

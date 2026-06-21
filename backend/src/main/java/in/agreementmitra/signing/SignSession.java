package in.agreementmitra.signing;

import java.util.List;

/**
 * Handle returned after creating a signing request with a provider. Multi-invitee: carries the
 * vendor document id plus one signing URL per invitee.
 *
 * @param providerDocumentId the vendor's document id for this request (store it)
 * @param invitees one entry per invitee, in the same order as the request
 */
public record SignSession(String providerDocumentId, List<InviteeSession> invitees) {

  /**
   * The per-invitee result of creating a signing request.
   *
   * @param email the invitee this URL belongs to (used to map back to the canonical signer)
   * @param signUrl the URL to show that signer — a bearer capability; never log it
   * @param expiryDate when the signing URL expires (vendor-formatted string)
   */
  public record InviteeSession(String email, String signUrl, String expiryDate) {}
}

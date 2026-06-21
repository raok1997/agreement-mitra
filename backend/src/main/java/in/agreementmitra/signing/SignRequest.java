package in.agreementmitra.signing;

import java.util.List;

/**
 * Vendor-neutral request to start an eSign for one agreement. Multi-invitee: an agreement is
 * multi-party (owners + tenants), and one provider call creates the request for all of them. No
 * Aadhaar/OTP data lives here — each signer authenticates on the ESP page, not through our API.
 *
 * @param agreementId our internal agreement id
 * @param unsignedPdf bytes of the rendered, unsigned agreement (server-sourced — never a
 *     client-supplied field)
 * @param invitees the signers to invite, in a stable order
 */
public record SignRequest(String agreementId, byte[] unsignedPdf, List<Invitee> invitees) {

  /**
   * One signer to invite to the eSign.
   *
   * @param name display name
   * @param email where to send the signing invite
   * @param phone optional contact number (may be null in sandbox)
   * @param verifyName whether the provider should verify the signer's name against Aadhaar
   */
  public record Invitee(String name, String email, String phone, boolean verifyName) {}
}

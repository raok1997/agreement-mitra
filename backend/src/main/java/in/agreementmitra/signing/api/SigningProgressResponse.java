package in.agreementmitra.signing.api;

import java.util.List;
import java.util.UUID;

/**
 * Per-party signing progress for one agreement: each party's own status alongside the aggregate
 * state, so a customer can see that (say) the owner has signed and the tenant has not - rather than
 * only "in progress".
 *
 * <p><b>What this deliberately does not carry.</b> No eKYC-derived signer data returned by the
 * provider (the name read from Aadhaar, given name, postal code, or name-match score), no signing
 * URL (a bearer capability that belongs to one party alone), and no provider credential. Parties
 * are identified by their canonical signer id and role, which are our own captured data, not the
 * provider's.
 *
 * @param agreementId the agreement
 * @param status the aggregate display status
 * @param parties one entry per party, in signing order
 */
public record SigningProgressResponse(
    UUID agreementId, AgreementDisplayStatus status, List<PartyProgress> parties) {

  /**
   * One party's own signing sub-state.
   *
   * @param signerId the canonical signer row this progress belongs to
   * @param role {@code OWNER} / {@code TENANT}
   * @param status {@code PENDING} / {@code SIGNED} / {@code REJECTED} / {@code EXPIRED}
   */
  public record PartyProgress(UUID signerId, String role, String status) {}
}

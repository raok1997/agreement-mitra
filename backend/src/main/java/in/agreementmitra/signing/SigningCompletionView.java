package in.agreementmitra.signing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the fulfilment side needs to know about one signing request, read through the
 * module-root {@link SigningRequestQuery} seam so the delivery sub-package never reaches into the
 * {@code signingrequest} package's entities.
 *
 * <p>It deliberately carries the <b>invited</b> address per party rather than the draft-time one:
 * the only address the signed agreement may be sent to is the address the invitation was issued to
 * AND at which that party then completed signing (design D2). {@link Party#verifiedEmail()} is the
 * single place that rule is expressed.
 *
 * @param signingRequestId the internal signing-request id
 * @param agreementId the agreement it belongs to
 * @param status the signing FSM state (read-only here - fulfilment never changes it)
 * @param signedPdfKey object-storage key of the signed PDF, or {@code null} until stored
 * @param parties one entry per invited party
 */
public record SigningCompletionView(
    UUID signingRequestId,
    UUID agreementId,
    SignatureStatus status,
    String signedPdfKey,
    List<Party> parties) {

  /** True once signing has completed successfully AND the signed artifact is stored. */
  public boolean deliverable() {
    return status == SignatureStatus.SIGNED && signedPdfKey != null;
  }

  /**
   * One party's delivery-relevant facts.
   *
   * @param signerId the canonical signer id
   * @param invitedEmail the address the signing invitation was issued to, or {@code null} for a row
   *     created before invited addresses were recorded
   * @param status that party's own signing sub-state
   */
  public record Party(UUID signerId, String invitedEmail, InviteeStatus status) {

    /**
     * The <b>signing-verified</b> address, or empty.
     *
     * <p>Present only when an invitation was issued to an address and that same party then
     * completed signing. An address someone typed at draft time is not evidence of anything; an
     * address that received an invitation and at which a party subsequently passed Aadhaar
     * authentication is. There is no fallback here on purpose - a party with no verified address is
     * escalated to staff, never guessed at, because the alternative is emailing both parties'
     * names, the property address, the financial terms and a stamp certificate to a stranger.
     */
    public Optional<String> verifiedEmail() {
      if (status != InviteeStatus.SIGNED) {
        return Optional.empty();
      }
      return invitedEmail == null || invitedEmail.isBlank()
          ? Optional.empty()
          : Optional.of(invitedEmail);
    }

    /** Never renders the address. */
    @Override
    public String toString() {
      return "Party{signerId=" + signerId + ", status=" + status + "}";
    }
  }

  /** Ids + status only - never a party address. */
  @Override
  public String toString() {
    return "SigningCompletionView{signingRequestId="
        + signingRequestId
        + ", agreementId="
        + agreementId
        + ", status="
        + status
        + ", parties="
        + parties.size()
        + "}";
  }
}

package in.agreementmitra.signing;

/**
 * Vendor-neutral status of a single invitee on a signing request — a sub-state of the aggregate's
 * {@link SignatureStatus#SIGN_REQUESTED}. Deliberately a <em>distinct</em> type from {@link
 * SignatureStatus} even though both spell {@code SIGNED}/{@code EXPIRED}: they live at different
 * levels (per-invitee vs whole-request) and must never be cross-assigned. The aggregate FSM is
 * derived by aggregating these (see {@code SigningRequest}).
 */
public enum InviteeStatus {
  /** Not yet acted on (sent / in flight / unknown). */
  PENDING,
  /** This invitee has signed. */
  SIGNED,
  /** This invitee rejected, or certificate verification failed. */
  REJECTED,
  /** This invitee's invitation expired. */
  EXPIRED
}

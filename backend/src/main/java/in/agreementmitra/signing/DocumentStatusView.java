package in.agreementmitra.signing;

import java.util.List;

/**
 * Vendor-neutral authoritative status of a document, carrying one entry per invitee. Returned by
 * {@link EsignProvider#getStatus(String)} (the source of truth for both the webhook and the
 * reconciliation paths). The aggregate FSM decision is computed from the multiset of per-invitee
 * statuses here (correlation-independent); the per-invitee correlation token is used only to
 * persist each status onto the matching row for display.
 *
 * @param invitees one entry per invitee, in provider order
 */
public record DocumentStatusView(List<InviteeStatusView> invitees) {

  /**
   * One invitee's authoritative status plus the tokens to correlate it back to a persisted invitee
   * row: the provider's per-invitee id when available (preferred), otherwise the {@code ordinal}
   * (position) as a fallback.
   *
   * @param providerInviteeId the provider's per-invitee identifier, or {@code null} if not exposed
   * @param ordinal zero-based position in provider order (correlation fallback)
   * @param status the vendor-neutral per-invitee status
   */
  public record InviteeStatusView(String providerInviteeId, int ordinal, InviteeStatus status) {}
}

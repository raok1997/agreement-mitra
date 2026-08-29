package in.agreementmitra.signing;

/**
 * The lifecycle of ONE recipient's delivery of ONE artifact. Deliberately separate from the signing
 * FSM: a bounced mailbox does not un-sign an agreement, so nothing here can move a signing request
 * off {@code SIGNED}.
 *
 * <p>Recipients progress independently (design D3) - "delivered to the owner, hard-bounced for the
 * tenant" is a real and actionable state, and a single boolean could not express it.
 */
public enum DeliveryStatus {

  /** Created, or returned here after a retryable failure. The only claimable state. */
  PENDING,

  /**
   * Claimed by an attempt that is in flight. The claim is a guarded conditional update taken
   * <b>before</b> the message is handed to the provider (design D4), so a re-entered completion
   * path and a concurrent attempt both find the row already claimed and send nothing.
   */
  IN_PROGRESS,

  /**
   * The provider accepted the message. <b>Not</b> proof it arrived: plain SMTP has no bounce
   * channel, and ZeptoMail's bounce webhook (which would close that gap) is out of scope here.
   */
  SENT,

  /** Permanently failed, or out of attempts. Terminal until staff deliberately re-send. */
  FAILED,

  /**
   * No signing-verified address could be resolved for this party, so nothing was sent and nothing
   * was guessed (design D2). Escalated to staff rather than falling back to a draft-time address.
   */
  UNRESOLVABLE;

  /** Whether a claim may be attempted from this state. */
  public boolean claimable() {
    return this == PENDING;
  }

  /** Whether this recipient still has outstanding work, which is what blocks closure. */
  public boolean outstanding() {
    return this != SENT;
  }

  /** Whether staff should be looking at this row. */
  public boolean needsAttention() {
    return this == FAILED || this == UNRESOLVABLE;
  }
}

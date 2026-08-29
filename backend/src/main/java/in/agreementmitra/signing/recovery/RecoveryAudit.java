package in.agreementmitra.signing.recovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded recovery request.
 *
 * <p>The endpoint answers identically whatever happens (design D1), so its responses carry no
 * operational signal at all. This row is where the signal lives: what was asked for, what we
 * decided, and how many people we told. Without it, an operator investigating abuse would have
 * nothing but a uniform stream of 202s.
 *
 * <p><b>Records the outcome, never the credential.</b> There is no link column and no token column,
 * because the link is the agreement identifier and writing it here would turn the audit table into
 * a second place that grants access. The recipient is stored redacted, matching the delivery logs.
 */
@Entity
@Table(name = "recovery_audit")
class RecoveryAudit {

  @Id private UUID id;

  @Column(name = "reference", nullable = false, updatable = false, length = 16)
  private String reference;

  @Column(name = "agreement_id", updatable = false)
  private UUID agreementId;

  @Column(name = "outcome", nullable = false, updatable = false, length = 32)
  private String outcome;

  @Column(name = "recipients_sent", nullable = false, updatable = false)
  private int recipientsSent;

  @Column(name = "recipient_redacted", updatable = false, length = 128)
  private String recipientRedacted;

  @Column(name = "requester_fingerprint", updatable = false, length = 128)
  private String requesterFingerprint;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  protected RecoveryAudit() {
    // JPA
  }

  private RecoveryAudit(
      String reference,
      UUID agreementId,
      RecoveryOutcome outcome,
      int recipientsSent,
      String recipientRedacted,
      String requesterFingerprint,
      Instant requestedAt) {
    this.id = UUID.randomUUID();
    this.reference = reference;
    this.agreementId = agreementId;
    this.outcome = outcome.name();
    this.recipientsSent = recipientsSent;
    this.recipientRedacted = recipientRedacted;
    this.requesterFingerprint = requesterFingerprint;
    this.requestedAt = requestedAt;
  }

  static RecoveryAudit of(
      String reference,
      UUID agreementId,
      RecoveryOutcome outcome,
      int recipientsSent,
      String recipientRedacted,
      String requesterFingerprint,
      Instant requestedAt) {
    return new RecoveryAudit(
        reference,
        agreementId,
        outcome,
        recipientsSent,
        recipientRedacted,
        requesterFingerprint,
        requestedAt);
  }

  UUID getId() {
    return id;
  }

  String outcome() {
    return outcome;
  }

  int recipientsSent() {
    return recipientsSent;
  }
}

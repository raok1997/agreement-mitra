package in.agreementmitra.signing.signingrequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One recorded stamp-intake attempt: who tried, against which agreement, what happened, and when.
 * Written for <b>rejected</b> attempts as well as accepted ones - a refused upload is exactly the
 * event an operational review needs to see, and an audit trail that only records successes is not
 * an audit trail.
 *
 * <p>Deliberately carries <b>no certificate number, no scan bytes, and no certificate metadata</b>.
 * The certificate number is the single-use token evidencing duty payment and the scan names the
 * parties; neither belongs in a row that is read far more widely than the agreement itself. The
 * submitted reference is kept (normalised and truncated by the writer) so an attempt against an
 * unknown reference is still traceable.
 *
 * <p>Id is app-assigned in the factory and the row implements {@link Persistable} with a transient
 * {@code isNew} flag, mirroring the other aggregates, so an app-assigned id does not trigger a
 * phantom {@code SELECT} before {@code INSERT}.
 */
@Entity
@Table(name = "stamp_intake_audit")
class StampIntakeAudit implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "staff_identity_id", nullable = false)
  private UUID staffIdentityId;

  /** Null when the submitted reference resolved to no agreement. */
  @Column(name = "agreement_id")
  private UUID agreementId;

  @Column(name = "submitted_reference", length = 32)
  private String submittedReference;

  @Column(name = "outcome", nullable = false, length = 48)
  private String outcome;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Transient private boolean isNew = true;

  protected StampIntakeAudit() {
    // JPA
  }

  private StampIntakeAudit(
      UUID id,
      UUID staffIdentityId,
      UUID agreementId,
      String submittedReference,
      String outcome,
      Instant occurredAt) {
    this.id = id;
    this.staffIdentityId = staffIdentityId;
    this.agreementId = agreementId;
    this.submittedReference = submittedReference;
    this.outcome = outcome;
    this.occurredAt = occurredAt;
  }

  static StampIntakeAudit record(
      UUID staffIdentityId, UUID agreementId, String submittedReference, String outcome) {
    return new StampIntakeAudit(
        UUID.randomUUID(),
        staffIdentityId,
        agreementId,
        submittedReference,
        outcome,
        Instant.now());
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostPersist
  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  UUID staffIdentityId() {
    return staffIdentityId;
  }

  UUID agreementId() {
    return agreementId;
  }

  String outcome() {
    return outcome;
  }

  Instant occurredAt() {
    return occurredAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof StampIntakeAudit other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Ids + outcome only - never the submitted reference or any certificate value.
    return "StampIntakeAudit{id=" + id + ", outcome=" + outcome + "}";
  }
}

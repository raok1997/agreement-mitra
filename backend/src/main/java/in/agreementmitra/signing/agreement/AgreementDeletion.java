package in.agreementmitra.signing.agreement;

import in.agreementmitra.AgreementIds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * The record that an owner deleted an unpaid draft (delete-draft-agreement, design D10): the
 * agreement id, its tracking reference, the owner and the time. Write-once. Carries no party name,
 * contact, address or money value - but it is pseudonymous personal data (the owner id joins to the
 * account, and the reference was emailed to the parties); see the V23 header for purpose, readers
 * and retention.
 */
@Entity
@Table(name = "agreement_deletion")
class AgreementDeletion implements Persistable<UUID> {

  @Id
  @Column(name = "agreement_id", updatable = false)
  private UUID agreementId;

  @Column(name = "tracking_reference", nullable = false, updatable = false, length = 16)
  private String trackingReference;

  @Column(name = "owner_identity_id", nullable = false, updatable = false)
  private UUID ownerIdentityId;

  @Column(name = "deleted_at", nullable = false, updatable = false)
  private Instant deletedAt;

  @Transient private boolean isNew = true;

  protected AgreementDeletion() {
    // JPA
  }

  static AgreementDeletion of(Agreement agreement, Instant deletedAt) {
    AgreementDeletion deletion = new AgreementDeletion();
    deletion.agreementId = agreement.getId();
    deletion.trackingReference = agreement.trackingReference();
    deletion.ownerIdentityId = agreement.ownerIdentityId();
    deletion.deletedAt = deletedAt;
    return deletion;
  }

  @Override
  public UUID getId() {
    return agreementId;
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

  String trackingReference() {
    return trackingReference;
  }

  UUID ownerIdentityId() {
    return ownerIdentityId;
  }

  Instant deletedAt() {
    return deletedAt;
  }

  @Override
  public String toString() {
    return "AgreementDeletion{agreementId=" + AgreementIds.redact(agreementId) + "}";
  }
}

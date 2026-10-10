package in.agreementmitra.signing.staffalert;

import in.agreementmitra.AgreementIds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Delivery state of the one staff alert an agreement gets once the gateway has marked a payment
 * order for it paid. The agreement is the key.
 *
 * <p><b>Read-only as an entity.</b> Rows are created by a native upsert and changed only by
 * conditional updates in {@link StaffAlertRepository}; nothing loads one, mutates it and saves it,
 * which is why there are no setters and no {@code @Version}.
 *
 * <p>{@link #toString()} is redacting: the agreement id is a bearer credential.
 */
@Entity
@Table(name = "staff_alert")
class StaffAlert {

  @Id
  @Column(name = "agreement_id", nullable = false, updatable = false)
  private UUID agreementId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private StaffAlertStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected StaffAlert() {}

  UUID agreementId() {
    return agreementId;
  }

  StaffAlertStatus status() {
    return status;
  }

  int attempts() {
    return attempts;
  }

  Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant sentAt() {
    return sentAt;
  }

  @Override
  public String toString() {
    return "StaffAlert{agreementId="
        + AgreementIds.redact(agreementId)
        + ", status="
        + status
        + ", attempts="
        + attempts
        + "}";
  }
}

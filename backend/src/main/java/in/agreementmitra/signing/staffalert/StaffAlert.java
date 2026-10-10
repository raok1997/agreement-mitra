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
 * Delivery state of one staff alert. An agreement can hold several: one {@link
 * StaffAlertKind#ORDER_PAID} once the gateway has marked a payment order for it paid, and one
 * {@link StaffAlertKind#DUPLICATE_PAYMENT} for each surplus payment order.
 *
 * <p><b>The key is the alert's subject</b>: the agreement id for a paid-order alert (so there is at
 * most one per agreement, enforced by a CHECK), the payment order id for a duplicate-payment alert
 * (one per surplus order).
 *
 * <p><b>Read-only as an entity.</b> Rows are created by a native upsert and changed only by
 * conditional updates in {@link StaffAlertRepository}; nothing loads one, mutates it and saves it,
 * which is why there are no setters and no {@code @Version}.
 *
 * <p>{@link #toString()} is redacting and never prints the key: the agreement id is a bearer
 * credential, and for a paid-order alert the key <em>is</em> the agreement id.
 */
@Entity
@Table(name = "staff_alert")
class StaffAlert {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 24)
  private StaffAlertKind kind;

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

  UUID id() {
    return id;
  }

  StaffAlertKind kind() {
    return kind;
  }

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
    return "StaffAlert{kind="
        + kind
        + ", agreementId="
        + AgreementIds.redact(agreementId)
        + ", status="
        + status
        + ", attempts="
        + attempts
        + "}";
  }
}

package in.agreementmitra.signing.delivery;

import in.agreementmitra.signing.DeliveryArtifact;
import in.agreementmitra.signing.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One recipient's delivery of one signed artifact (design D3). Per-recipient rather than a flag on
 * the signing request, because recipients fail independently: one party's mailbox bouncing must not
 * block the other's delivery, must not retry the successful one, and must be individually
 * diagnosable and individually re-sendable.
 *
 * <p><b>This row is the exactly-once mechanism</b> (design D4). The completion path is re-entered
 * by both the webhook and the reconciliation job, so nothing may rely on being called once; the row
 * is claimed by a guarded conditional update <em>before</em> any message is handed to the provider,
 * and a re-entering or concurrent attempt finds it already claimed. The claim itself lives in
 * {@link DeliveryPersistence}, where it can be a single atomic statement - an in-memory
 * read-then-write here would race.
 *
 * <p>Nothing in this aggregate can move a signing request off {@code SIGNED}: a signature is a
 * legal fact and an undeliverable mailbox does not undo it.
 *
 * <p>Id is app-assigned in the factory; {@link Persistable} with a transient {@code isNew} flag
 * avoids a phantom {@code SELECT} before {@code INSERT}, matching the other aggregates here.
 */
@Entity
@Table(name = "signed_document_delivery")
class SignedDocumentDelivery implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "agreement_id", nullable = false)
  private UUID agreementId;

  @Column(name = "signing_request_id", nullable = false)
  private UUID signingRequestId;

  @Column(name = "signer_id", nullable = false)
  private UUID signerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "artifact", nullable = false, length = 32)
  private DeliveryArtifact artifact;

  /**
   * The signing-verified address this row delivers to, or null when none could be resolved. Party
   * PII: redacted in every log line and never returned unredacted by any API.
   */
  @Column(name = "recipient_email")
  private String recipientEmail;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private DeliveryStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  /** A short fixed token, never a provider message and never document content. */
  @Column(name = "last_error", length = 128)
  private String lastError;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  /**
   * True when the document exceeded the attachment ceiling and the party was sent a notification
   * pointing at the in-app copy instead (design D6). "We emailed them the agreement" and "we told
   * them where to fetch it" are different facts, and a support conversation turns on which
   * happened.
   */
  @Column(name = "notification_only", nullable = false)
  private boolean notificationOnly;

  @Column(name = "resend_count", nullable = false)
  private int resendCount;

  @Column(name = "resent_by_identity_id")
  private UUID resentByIdentityId;

  @Column(name = "resent_at")
  private Instant resentAt;

  @Version private long version;

  @Transient private boolean isNew = true;

  protected SignedDocumentDelivery() {
    // JPA
  }

  private SignedDocumentDelivery(
      UUID id,
      UUID agreementId,
      UUID signingRequestId,
      UUID signerId,
      DeliveryArtifact artifact,
      String recipientEmail,
      DeliveryStatus status,
      Instant createdAt) {
    this.id = id;
    this.agreementId = agreementId;
    this.signingRequestId = signingRequestId;
    this.signerId = signerId;
    this.artifact = artifact;
    this.recipientEmail = recipientEmail;
    this.status = status;
    this.createdAt = createdAt;
    this.nextAttemptAt = createdAt;
  }

  /** A resolvable recipient: a signing-verified address exists, so this row is sendable. */
  static SignedDocumentDelivery pending(
      UUID agreementId,
      UUID signingRequestId,
      UUID signerId,
      DeliveryArtifact artifact,
      String recipientEmail) {
    return new SignedDocumentDelivery(
        UUID.randomUUID(),
        agreementId,
        signingRequestId,
        signerId,
        artifact,
        recipientEmail,
        DeliveryStatus.PENDING,
        Instant.now());
  }

  /**
   * No signing-verified address could be resolved. Recorded rather than skipped, and NOT filled in
   * from the draft record: the point of the record is that staff can see this party was never
   * delivered to, and the point of not guessing is that the signed agreement names both parties,
   * the property and the money.
   */
  static SignedDocumentDelivery unresolvable(
      UUID agreementId, UUID signingRequestId, UUID signerId, DeliveryArtifact artifact) {
    SignedDocumentDelivery row =
        new SignedDocumentDelivery(
            UUID.randomUUID(),
            agreementId,
            signingRequestId,
            signerId,
            artifact,
            null,
            DeliveryStatus.UNRESOLVABLE,
            Instant.now());
    row.lastError = "no-signing-verified-address";
    row.nextAttemptAt = null;
    return row;
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

  UUID agreementId() {
    return agreementId;
  }

  UUID signingRequestId() {
    return signingRequestId;
  }

  UUID signerId() {
    return signerId;
  }

  DeliveryArtifact artifact() {
    return artifact;
  }

  String recipientEmail() {
    return recipientEmail;
  }

  DeliveryStatus status() {
    return status;
  }

  int attempts() {
    return attempts;
  }

  String lastError() {
    return lastError;
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

  boolean notificationOnly() {
    return notificationOnly;
  }

  int resendCount() {
    return resendCount;
  }

  Instant resentAt() {
    return resentAt;
  }

  /**
   * Id + status only. Never the recipient address - an accidental {@code log.info("{}", row)} must
   * not leak a party's mailbox.
   */
  @Override
  public String toString() {
    return "SignedDocumentDelivery{id=" + id + ", status=" + status + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof SignedDocumentDelivery other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}

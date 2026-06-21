package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.InviteeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A per-signer signing handle on a {@link SigningRequest}: the provider signing URL (a bearer
 * capability — never logged) and its expiry, keyed back to the canonical {@code signer} row by id.
 * The {@code signer_id} is stored as a plain id (with a DB-level FK) rather than a JPA association,
 * to avoid reaching into the {@code agreement} package's entities. Persisted via cascade from the
 * aggregate; never saved on its own.
 */
@Entity
@Table(name = "signing_request_invitee")
class SigningRequestInvitee {

  @Id private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "signing_request_id", nullable = false)
  private SigningRequest signingRequest;

  @Column(name = "signer_id", nullable = false)
  private UUID signerId;

  @Column(name = "sign_url", nullable = false)
  private String signUrl;

  @Column(name = "expiry")
  private String expiry;

  /** Create-time ordinal (provider-response order); the status-correlation fallback. */
  @Column(name = "signing_order")
  private Short signingOrder;

  /** The provider's per-invitee identifier; the preferred status-correlation key (may be null). */
  @Column(name = "provider_invitee_id")
  private String providerInviteeId;

  /** Per-invitee completion sub-state; driven off the authoritative Details read. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16)
  private InviteeStatus status = InviteeStatus.PENDING;

  protected SigningRequestInvitee() {
    // JPA
  }

  private SigningRequestInvitee(
      UUID id,
      UUID signerId,
      String signUrl,
      String expiry,
      Short signingOrder,
      String providerInviteeId) {
    this.id = id;
    this.signerId = signerId;
    this.signUrl = signUrl;
    this.expiry = expiry;
    this.signingOrder = signingOrder;
    this.providerInviteeId = providerInviteeId;
    this.status = InviteeStatus.PENDING;
  }

  static SigningRequestInvitee create(
      UUID signerId, String signUrl, String expiry, int signingOrder, String providerInviteeId) {
    return new SigningRequestInvitee(
        UUID.randomUUID(), signerId, signUrl, expiry, (short) signingOrder, providerInviteeId);
  }

  void attachTo(SigningRequest signingRequest) {
    this.signingRequest = signingRequest;
  }

  UUID id() {
    return id;
  }

  UUID signerId() {
    return signerId;
  }

  String signUrl() {
    return signUrl;
  }

  String expiry() {
    return expiry;
  }

  Short signingOrder() {
    return signingOrder;
  }

  String providerInviteeId() {
    return providerInviteeId;
  }

  InviteeStatus status() {
    return status;
  }

  /**
   * Set the per-invitee status (called by the aggregate during status correlation, not directly).
   */
  void updateStatus(InviteeStatus status) {
    this.status = status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof SigningRequestInvitee other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id + signerId only — never the signing URL (a bearer capability).
    return "SigningRequestInvitee{id=" + id + ", signerId=" + signerId + "}";
  }
}

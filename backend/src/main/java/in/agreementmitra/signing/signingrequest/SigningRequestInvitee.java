package in.agreementmitra.signing.signingrequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

  protected SigningRequestInvitee() {
    // JPA
  }

  private SigningRequestInvitee(UUID id, UUID signerId, String signUrl, String expiry) {
    this.id = id;
    this.signerId = signerId;
    this.signUrl = signUrl;
    this.expiry = expiry;
  }

  static SigningRequestInvitee create(UUID signerId, String signUrl, String expiry) {
    return new SigningRequestInvitee(UUID.randomUUID(), signerId, signUrl, expiry);
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

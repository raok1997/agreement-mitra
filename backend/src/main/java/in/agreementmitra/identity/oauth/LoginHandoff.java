package in.agreementmitra.identity.oauth;

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
 * The single-use, short-lived code carried in the SPA redirect after a successful callback. Stores
 * only the hash of the raw handoff (never the raw value) bound to the resolved identity. It carries
 * NO session material -- it is exchanged exactly once at {@code /session/exchange} for an opaque
 * session, then consumed; a leaked redirect is inert after first use or expiry.
 *
 * <p>{@code toString()} is id-only -- never the hash or the identity.
 */
@Entity
@Table(name = "login_handoff")
class LoginHandoff implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "handoff_hash", nullable = false)
  private String handoffHash;

  @Column(name = "identity_id", nullable = false)
  private UUID identityId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Transient private boolean isNew = true;

  protected LoginHandoff() {
    // JPA
  }

  private LoginHandoff(
      UUID id, String handoffHash, UUID identityId, Instant createdAt, Instant expiresAt) {
    this.id = id;
    this.handoffHash = handoffHash;
    this.identityId = identityId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  static LoginHandoff create(String handoffHash, UUID identityId, Instant expiresAt) {
    return new LoginHandoff(UUID.randomUUID(), handoffHash, identityId, Instant.now(), expiresAt);
  }

  @Override
  public UUID getId() {
    return id;
  }

  UUID identityId() {
    return identityId;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof LoginHandoff other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- never the handoff hash or identity.
    return "LoginHandoff{id=" + id + "}";
  }
}

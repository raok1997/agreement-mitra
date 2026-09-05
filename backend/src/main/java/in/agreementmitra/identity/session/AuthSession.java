package in.agreementmitra.identity.session;

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
 * An opaque, server-side, revocable, expiring session bound to an identity. The high-entropy
 * session value is returned to the client exactly once (at exchange) and NEVER persisted -- only
 * its SHA-256 (keyed) hash is stored here, so a database leak cannot reconstruct a usable bearer
 * value. A presented {@code Authorization: Bearer} value is authenticated by re-hashing and looking
 * up a live, unexpired row.
 *
 * <p>Provider-agnostic shared infra: a future mobile-OTP login mints the identical session. {@code
 * toString()} is id-only -- never the value hash or identity.
 */
@Entity
@Table(name = "auth_session")
class AuthSession implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "identity_id", nullable = false)
  private UUID identityId;

  @Column(name = "value_hash", nullable = false)
  private String valueHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Transient private boolean isNew = true;

  protected AuthSession() {
    // JPA
  }

  private AuthSession(
      UUID id, UUID identityId, String valueHash, Instant createdAt, Instant expiresAt) {
    this.id = id;
    this.identityId = identityId;
    this.valueHash = valueHash;
    this.createdAt = createdAt;
    this.lastSeenAt = createdAt;
    this.expiresAt = expiresAt;
  }

  static AuthSession create(UUID identityId, String valueHash, Instant expiresAt) {
    return new AuthSession(UUID.randomUUID(), identityId, valueHash, Instant.now(), expiresAt);
  }

  @Override
  public UUID getId() {
    return id;
  }

  UUID identityId() {
    return identityId;
  }

  Instant expiresAt() {
    return expiresAt;
  }

  boolean isLive(Instant now) {
    return expiresAt.isAfter(now);
  }

  /** Record activity; the caller persists the row. */
  void touch(Instant now) {
    this.lastSeenAt = now;
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
    return o instanceof AuthSession other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- never the value hash or identity.
    return "AuthSession{id=" + id + "}";
  }
}

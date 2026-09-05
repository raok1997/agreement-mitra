package in.agreementmitra.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A provider-agnostic application user: the durable identity that later work (CR-B) owns agreements
 * by. Carries a server-assigned UUID, an optional display name, and an optional email (for display
 * only); the proof-of-who lives in child {@code IdentityCredential} rows, each {@code (provider,
 * providerSubject)}. Google is the first provider; a future mobile-OTP credential attaches to the
 * same identity with no schema change.
 *
 * <p>Id is app-assigned in the factory (stable equality from birth) and the aggregate implements
 * {@link Persistable} with a transient {@code isNew} flag so an app-assigned id does not trigger a
 * phantom {@code SELECT} before {@code INSERT} -- mirroring {@code Agreement}. {@code toString()}
 * is id-only: it never carries the email.
 */
@Entity
@Table(name = "identity")
class Identity implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "email")
  private String email;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * What this account is allowed to do (design D7). Server-managed: assigned {@link
   * IdentityRole#CUSTOMER} at creation and changed only by a deliberate out-of-band database
   * action. There is intentionally NO setter and no factory parameter -- neither a request body, a
   * header, nor an OAuth claim can reach this field, so privilege can never be self-assigned.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private IdentityRole role = IdentityRole.CUSTOMER;

  @Transient private boolean isNew = true;

  protected Identity() {
    // JPA
  }

  private Identity(UUID id, String displayName, String email, Instant createdAt) {
    this.id = id;
    this.displayName = displayName;
    this.email = email;
    this.createdAt = createdAt;
  }

  /** Create a fresh identity with an app-assigned id. Display name/email are display-only. */
  static Identity create(String displayName, String email) {
    return new Identity(UUID.randomUUID(), displayName, email, Instant.now());
  }

  @Override
  public UUID getId() {
    return id;
  }

  String displayName() {
    return displayName;
  }

  String email() {
    return email;
  }

  Instant createdAt() {
    return createdAt;
  }

  /** The server-managed role. Never null: every row defaults to {@link IdentityRole#CUSTOMER}. */
  IdentityRole role() {
    return role == null ? IdentityRole.CUSTOMER : role;
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
    return o instanceof Identity other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- never the email (PII).
    return "Identity{id=" + id + "}";
  }
}

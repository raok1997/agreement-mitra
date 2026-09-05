package in.agreementmitra.identity;

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
 * A proof-of-identity from one external provider, keyed by {@code (provider, providerSubject)} with
 * a database uniqueness constraint. Login find-or-creates by that pair: the first login for a
 * subject inserts the credential (and its parent {@link Identity}); later logins reuse it. Holds
 * the subject's email + verification flag as reported by the provider (display/audit only).
 *
 * <p>The parent link is a plain {@code identityId} UUID (not a JPA association) so the child stays
 * a simple row; the {@code Identity} aggregate does not eagerly own the collection. {@code
 * toString()} is id-only -- never the subject or email.
 */
@Entity
@Table(name = "identity_credential")
class IdentityCredential implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "identity_id", nullable = false)
  private UUID identityId;

  @Column(name = "provider", nullable = false)
  private String provider;

  @Column(name = "provider_subject", nullable = false)
  private String providerSubject;

  @Column(name = "email")
  private String email;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  protected IdentityCredential() {
    // JPA
  }

  private IdentityCredential(
      UUID id,
      UUID identityId,
      String provider,
      String providerSubject,
      String email,
      boolean emailVerified,
      Instant createdAt) {
    this.id = id;
    this.identityId = identityId;
    this.provider = provider;
    this.providerSubject = providerSubject;
    this.email = email;
    this.emailVerified = emailVerified;
    this.createdAt = createdAt;
  }

  static IdentityCredential create(
      UUID identityId,
      String provider,
      String providerSubject,
      String email,
      boolean emailVerified) {
    return new IdentityCredential(
        UUID.randomUUID(),
        identityId,
        provider,
        providerSubject,
        email,
        emailVerified,
        Instant.now());
  }

  @Override
  public UUID getId() {
    return id;
  }

  UUID identityId() {
    return identityId;
  }

  String provider() {
    return provider;
  }

  String providerSubject() {
    return providerSubject;
  }

  String email() {
    return email;
  }

  boolean emailVerified() {
    return emailVerified;
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
    return o instanceof IdentityCredential other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- never the provider subject or email (PII / enumeration surface).
    return "IdentityCredential{id=" + id + "}";
  }
}

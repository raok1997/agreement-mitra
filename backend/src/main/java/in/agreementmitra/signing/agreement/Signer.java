package in.agreementmitra.signing.agreement;

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
 * A signer on an {@link Agreement} — one owner or tenant who will authenticate via Aadhaar+OTP. An
 * addressable child entity (its own id) so a later CR can hang per-signer provider session + state
 * off this row. Persisted via cascade from the aggregate; never saved on its own.
 */
@Entity
@Table(name = "signer")
class Signer {

  @Id private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "agreement_id", nullable = false)
  private Agreement agreement;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  protected Signer() {
    // JPA
  }

  private Signer(UUID id, Agreement agreement, String name, String email, Role role) {
    this.id = id;
    this.agreement = agreement;
    this.name = name;
    this.email = email;
    this.role = role;
  }

  static Signer create(Agreement agreement, String name, String email, Role role) {
    return new Signer(UUID.randomUUID(), agreement, name, email, role);
  }

  UUID id() {
    return id;
  }

  String name() {
    return name;
  }

  String email() {
    return email;
  }

  Role role() {
    return role;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof Signer other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id + role only — never name/email. Module-wide DEBUG logging must not leak signer PII.
    return "Signer{id=" + id + ", role=" + role + "}";
  }
}

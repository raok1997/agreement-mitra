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
 * addressable child entity (its own id) so the signing flow can hang per-signer provider session +
 * state off this row. Persisted via cascade from the aggregate; never saved on its own.
 *
 * <p>{@code name} is the full name as per Aadhaar (server-derived from first + last, or an
 * override) and is the name handed to the eSign provider. {@code firstName}/{@code lastName}/{@code
 * fatherName}/{@code currentAddress} are the structured capture fields. {@code email}/{@code
 * mobile} are optional at draft — a contact is required only before a signing request.
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

  @Column(name = "first_name", nullable = false)
  private String firstName;

  @Column(name = "last_name", nullable = false)
  private String lastName;

  @Column(name = "father_name", nullable = false)
  private String fatherName;

  @Column(name = "current_address", nullable = false)
  private String currentAddress;

  @Column private String email;

  @Column private String mobile;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  protected Signer() {
    // JPA
  }

  private Signer(
      UUID id,
      Agreement agreement,
      String name,
      String firstName,
      String lastName,
      String fatherName,
      String currentAddress,
      String email,
      String mobile,
      Role role) {
    this.id = id;
    this.agreement = agreement;
    this.name = name;
    this.firstName = firstName;
    this.lastName = lastName;
    this.fatherName = fatherName;
    this.currentAddress = currentAddress;
    this.email = email;
    this.mobile = mobile;
    this.role = role;
  }

  static Signer create(
      Agreement agreement,
      String name,
      String firstName,
      String lastName,
      String fatherName,
      String currentAddress,
      String email,
      String mobile,
      Role role) {
    return new Signer(
        UUID.randomUUID(),
        agreement,
        name,
        firstName,
        lastName,
        fatherName,
        currentAddress,
        email,
        mobile,
        role);
  }

  UUID id() {
    return id;
  }

  String name() {
    return name;
  }

  String firstName() {
    return firstName;
  }

  String lastName() {
    return lastName;
  }

  String fatherName() {
    return fatherName;
  }

  String currentAddress() {
    return currentAddress;
  }

  String email() {
    return email;
  }

  String mobile() {
    return mobile;
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
    // Id + role only — never name/parts/address/contact. Module-wide DEBUG must not leak PII.
    return "Signer{id=" + id + ", role=" + role + "}";
  }
}

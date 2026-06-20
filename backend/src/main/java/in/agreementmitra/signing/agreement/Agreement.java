package in.agreementmitra.signing.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Rental-agreement aggregate: a stable, persisted contract document. Status-less by design — a
 * signing <em>attempt</em> is a separate concern (its own aggregate + FSM, in the {@code
 * create-signing-request} change), so no {@code SignatureStatus} lives here.
 *
 * <p>Identity is application-assigned at construction ({@link #create}), not by a DB default, so an
 * Agreement has its id the instant it exists — testable without a DB round-trip. Column mappings
 * mirror {@code V2__agreement.sql} exactly so {@code ddl-auto: validate} passes against the schema.
 *
 * <p>This type is internal to the {@code signing} module (its sub-package carries no
 * {@code @NamedInterface}), so it is not part of the module's cross-module API.
 */
@Entity
@Table(name = "agreement")
public class Agreement {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "landlord_name", nullable = false)
  private String landlordName;

  @Column(name = "tenant_name", nullable = false)
  private String tenantName;

  @Column(name = "property_address", nullable = false)
  private String propertyAddress;

  @Column(name = "monthly_rent", nullable = false, precision = 12, scale = 2)
  private BigDecimal monthlyRent;

  @Column(name = "security_deposit", nullable = false, precision = 12, scale = 2)
  private BigDecimal securityDeposit;

  @Column(name = "term_months", nullable = false)
  private int termMonths;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Required by JPA; not for application use. */
  protected Agreement() {}

  private Agreement(
      UUID id,
      String landlordName,
      String tenantName,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      int termMonths,
      Instant createdAt) {
    this.id = id;
    this.landlordName = landlordName;
    this.tenantName = tenantName;
    this.propertyAddress = propertyAddress;
    this.monthlyRent = monthlyRent;
    this.securityDeposit = securityDeposit;
    this.termMonths = termMonths;
    this.createdAt = createdAt;
  }

  /**
   * Builds a new Agreement with a freshly assigned UUID identity and a {@code createdAt} stamped at
   * creation. Content is assumed already validated at the API boundary.
   */
  public static Agreement create(
      String landlordName,
      String tenantName,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      int termMonths) {
    return new Agreement(
        UUID.randomUUID(),
        landlordName,
        tenantName,
        propertyAddress,
        monthlyRent,
        securityDeposit,
        termMonths,
        Instant.now());
  }

  public UUID getId() {
    return id;
  }

  public String getLandlordName() {
    return landlordName;
  }

  public String getTenantName() {
    return tenantName;
  }

  public String getPropertyAddress() {
    return propertyAddress;
  }

  public BigDecimal getMonthlyRent() {
    return monthlyRent;
  }

  public BigDecimal getSecurityDeposit() {
    return securityDeposit;
  }

  public int getTermMonths() {
    return termMonths;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  // Identity is the application-assigned id: stable from construction (not flush-dependent), so
  // id-based equals/hashCode is safe and idiomatic here.
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof Agreement other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}

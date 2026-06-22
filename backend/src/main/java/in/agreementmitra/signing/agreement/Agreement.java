package in.agreementmitra.signing.agreement;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A rental agreement aggregate: the rental terms plus the set of owner/tenant signers. Status-less
 * by design — the signing-status FSM is introduced by a later CR. The aggregate root owns its
 * signers' lifecycle (cascade + orphan removal).
 *
 * <p>Id is app-assigned in the factory so it exists before persistence and equality is stable from
 * birth. Implements {@link Persistable} with a transient {@code isNew} flag so an app-assigned id
 * does not trigger a phantom {@code SELECT} before {@code INSERT} on {@code save()}.
 */
@Entity
@Table(name = "agreement")
class Agreement implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "property_address", nullable = false)
  private String propertyAddress;

  @Column(name = "monthly_rent", nullable = false)
  private BigDecimal monthlyRent;

  @Column(name = "security_deposit", nullable = false)
  private BigDecimal securityDeposit;

  @Column(name = "term_months", nullable = false)
  private int termMonths;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * Object-storage key of the uploaded draft PDF; null until a draft is attached. Server-managed —
   * never client-settable (set only via {@link #attachDraft}). The bytes live in object storage,
   * never here.
   */
  @Column(name = "draft_pdf_key")
  private String draftPdfKey;

  /**
   * Server-managed stamp data; null until a stamp is procured (during the first signing request).
   * Descriptive data only — the signing status lives on the signing-request FSM, not here.
   */
  @Embedded private StampInfo stampInfo;

  @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Signer> signers = new ArrayList<>();

  @Transient private boolean isNew = true;

  protected Agreement() {
    // JPA
  }

  private Agreement(
      UUID id,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      int termMonths,
      Instant createdAt) {
    this.id = id;
    this.propertyAddress = propertyAddress;
    this.monthlyRent = monthlyRent;
    this.securityDeposit = securityDeposit;
    this.termMonths = termMonths;
    this.createdAt = createdAt;
  }

  static Agreement create(
      String propertyAddress, BigDecimal monthlyRent, BigDecimal securityDeposit, int termMonths) {
    return new Agreement(
        UUID.randomUUID(),
        propertyAddress,
        monthlyRent,
        securityDeposit,
        termMonths,
        Instant.now());
  }

  /** Add a signer to the aggregate, wiring both sides of the relationship. */
  void addSigner(String name, String email, Role role) {
    signers.add(Signer.create(this, name, email, role));
  }

  /** Attach (or replace) the uploaded draft's object-storage key. Server-managed only. */
  void attachDraft(String draftPdfKey) {
    this.draftPdfKey = draftPdfKey;
  }

  /** Attach (or replace) the procured stamp data. Server-managed only. */
  void attachStamp(StampInfo stampInfo) {
    this.stampInfo = stampInfo;
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

  String propertyAddress() {
    return propertyAddress;
  }

  BigDecimal monthlyRent() {
    return monthlyRent;
  }

  BigDecimal securityDeposit() {
    return securityDeposit;
  }

  int termMonths() {
    return termMonths;
  }

  Instant createdAt() {
    return createdAt;
  }

  String draftPdfKey() {
    return draftPdfKey;
  }

  StampInfo stampInfo() {
    return stampInfo;
  }

  List<Signer> signers() {
    return Collections.unmodifiableList(signers);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof Agreement other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only — no signer PII (the signer collection holds name/email).
    return "Agreement{id=" + id + "}";
  }
}

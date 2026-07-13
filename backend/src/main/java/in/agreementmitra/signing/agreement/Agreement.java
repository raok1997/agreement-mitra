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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

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
   * Id of the catalog template selected for this agreement; null until one is chosen.
   * Server-managed -- never client-settable (set only via {@link #selectTemplate}). Held as a plain
   * {@link UUID} value sourced from the catalog selection at the {@code api} layer, so this
   * aggregate carries no {@code documents}-module type and the Modulith boundary stays clean. The
   * effective-template content-hash + layer-version pin is recorded separately at generate-as-draft
   * (document projection); this records only <em>which</em> template was chosen.
   */
  @Column(name = "template_id")
  private UUID templateId;

  /**
   * The effective-template <b>reproducibility pin</b>: the {@code contentHash} of the composed
   * template that drew the stored draft; null until generate-as-draft records it. Server-managed --
   * never client-settable (set only via {@link #pinEffectiveTemplate} after a successful full
   * render + draft storage). System-owned integrity metadata only (no party PII). Paired with
   * {@link #templateLayerVersions}, it makes a stored/signed draft reproducible so it can never be
   * silently re-rendered against newer layers.
   */
  @Column(name = "template_content_hash")
  private String templateContentHash;

  /**
   * The pin's resolved layer versions ({@code layerId -> version}) of every contributing layer;
   * null until generate-as-draft records it. Server-managed alongside {@link #templateContentHash};
   * mapped to a {@code jsonb} column. System-owned integrity metadata only (no party PII).
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "template_layer_versions")
  private Map<String, Integer> templateLayerVersions;

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
      LocalDate startDate,
      LocalDate endDate,
      Instant createdAt) {
    this.id = id;
    this.propertyAddress = propertyAddress;
    this.monthlyRent = monthlyRent;
    this.securityDeposit = securityDeposit;
    this.termMonths = termMonths;
    this.startDate = startDate;
    this.endDate = endDate;
    this.createdAt = createdAt;
  }

  static Agreement create(
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      LocalDate startDate,
      LocalDate endDate) {
    return new Agreement(
        UUID.randomUUID(),
        propertyAddress,
        monthlyRent,
        securityDeposit,
        TenancyDuration.months(startDate, endDate),
        startDate,
        endDate,
        Instant.now());
  }

  /** Add a signer to the aggregate, wiring both sides of the relationship. */
  void addSigner(
      String name,
      String firstName,
      String lastName,
      String fatherName,
      String currentAddress,
      String email,
      String mobile,
      Role role) {
    signers.add(
        Signer.create(
            this, name, firstName, lastName, fatherName, currentAddress, email, mobile, role));
  }

  /** Attach (or replace) the uploaded draft's object-storage key. Server-managed only. */
  void attachDraft(String draftPdfKey) {
    this.draftPdfKey = draftPdfKey;
  }

  /** Attach (or replace) the procured stamp data. Server-managed only. */
  void attachStamp(StampInfo stampInfo) {
    this.stampInfo = stampInfo;
  }

  /**
   * Record the selected catalog template's id. Server-managed only (the id is sourced from the
   * catalog selection on the server, never a client-settable body field). Takes a plain {@link
   * UUID} value so the aggregate stays free of any {@code documents}-module type.
   */
  void selectTemplate(UUID templateId) {
    this.templateId = templateId;
  }

  /**
   * Pin the effective template's identity ({@code contentHash} + resolved {@code layerId ->
   * version} map) that drew the generated draft, so the stored/signed draft is reproducible and can
   * never be silently re-rendered against newer layers. Server-managed only -- invoked at
   * generate-as-draft after a successful full render + draft storage, never from a client body.
   * Takes plain values (no {@code documents}-module type) to keep the aggregate free of that
   * module's types. The layer-map is defensively copied.
   */
  void pinEffectiveTemplate(String contentHash, Map<String, Integer> layerVersions) {
    this.templateContentHash = contentHash;
    this.templateLayerVersions = layerVersions == null ? null : Map.copyOf(layerVersions);
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

  LocalDate startDate() {
    return startDate;
  }

  LocalDate endDate() {
    return endDate;
  }

  Instant createdAt() {
    return createdAt;
  }

  String draftPdfKey() {
    return draftPdfKey;
  }

  UUID templateId() {
    return templateId;
  }

  String templateContentHash() {
    return templateContentHash;
  }

  Map<String, Integer> templateLayerVersions() {
    return templateLayerVersions == null
        ? null
        : Collections.unmodifiableMap(templateLayerVersions);
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

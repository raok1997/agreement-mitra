package in.agreementmitra.signing.agreement;

import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.ClosureState;
import in.agreementmitra.signing.PaymentState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
   * The agreement's <b>one</b> externally-visible reference (design D3): short, checksummed,
   * unique, and <b>immutable</b> ({@code updatable = false}, no setter, assigned once in the
   * factory). The customer is given it, staff quote it to attach the purchased e-stamp, and the
   * document's provenance line renders it - one value, so what the customer reads out is what staff
   * type in. The derived {@code AM-<LAST6>-<DDMMYY>} veneer that previously served as a second,
   * display-only number is gone; it was computed at render time and was not collision-free. See
   * {@link TrackingReference}.
   */
  @Column(name = "tracking_reference", nullable = false, updatable = false, length = 16)
  private String trackingReference;

  /**
   * Id of the {@code identity} that owns this agreement; null until an anonymous draft is claimed
   * (saved). Held as a plain {@link UUID} value sourced from the authenticated principal at the
   * {@code api} layer -- the aggregate carries no {@code identity}-module type, so the Modulith
   * boundary stays clean (mirroring how {@link #templateId} holds a template UUID). Server-managed
   * -- never a client-settable body field (set only via {@link #claimBy}); anti-mass-assignment.
   */
  @Column(name = "owner_identity_id")
  private UUID ownerIdentityId;

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
   * The agreement's full <b>capture state</b>: the flat working-set field map plus the added
   * optional-section titles the user entered on the guided form; null until create/edit stores it
   * (and null for legacy rows / API clients that send only the fixed fields). Server-managed
   * content held as a plain {@link CaptureState} value object (plain JDK types only -- no {@code
   * documents}-module type), mapped to a {@code jsonb} column exactly like {@link
   * #templateLayerVersions}. It is <b>user content</b>, validated at render by the {@code
   * documents} projection, never trusted blindly. Replaced wholesale on edit (like the party list).
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "capture_state")
  private CaptureState captureState;

  /**
   * Server-managed stamp data; null until staff upload a purchased e-stamp certificate. Descriptive
   * data only - the signing status lives on the signing-request FSM, not here.
   */
  @Embedded private StampInfo stampInfo;

  /**
   * Whether this agreement has been paid for (payment-gate CR). Server-managed: set only through
   * {@link #recordPayment} / {@link #waivePayment}, never from a client body field
   * (anti-mass-assignment). Starts {@link PaymentState#UNPAID} for every new agreement, and the
   * column is {@code NOT NULL DEFAULT 'UNPAID'} so no read has to treat null as a fourth state.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "payment_state", nullable = false, length = 16)
  private PaymentState paymentState = PaymentState.UNPAID;

  /** Amount received; null unless {@link #paymentState} is {@code PAID}. Server-managed. */
  @Column(name = "payment_amount")
  private BigDecimal paymentAmount;

  /** ISO-4217 currency of {@link #paymentAmount}; null unless {@code PAID}. Server-managed. */
  @Column(name = "payment_currency", length = 3)
  private String paymentCurrency;

  /**
   * The external payment reference; unique where present (a database unique index enforces it), so
   * one payment cannot be credited to two agreements. Null for a waiver. Server-managed.
   */
  @Column(name = "payment_reference", length = 128)
  private String paymentReference;

  /**
   * Who caused the payment transition - a STAFF identity id today. Held as a plain {@link UUID}
   * value so the aggregate carries no {@code identity}-module type. Server-managed.
   */
  @Column(name = "payment_actor_identity_id")
  private UUID paymentActorIdentityId;

  /** When the payment transition was recorded. Server-managed. */
  @Column(name = "payment_recorded_at")
  private Instant paymentRecordedAt;

  /**
   * Terminal <b>fulfilment</b> state (signed-delivery-and-closure CR, design D1): is there work
   * outstanding? Sits beside {@link #paymentState}, which already established that fulfilment state
   * lives on this aggregate.
   *
   * <p>This does <b>not</b> breach the status-less rule. That rule is specifically that the
   * agreement carries no <em>signing</em> status - the signing FSM answers "what happened to the
   * signatures" and its terminal states are a legal record. This answers an operational question
   * that continues past signing and also applies to an agreement that never signed at all. Worth
   * being explicit, because a future reader will otherwise see two status-shaped fields on a
   * supposedly status-less aggregate and assume drift.
   *
   * <p>Server-managed: set only through {@link #close}, never from a client body field. {@code NOT
   * NULL DEFAULT 'OPEN'} in the schema, so no read has to treat null as a third state.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "closure_state", nullable = false, length = 16)
  private ClosureState closureState = ClosureState.OPEN;

  /** When the agreement closed; null while {@code OPEN}. Server-managed. */
  @Column(name = "closed_at")
  private Instant closedAt;

  /**
   * Why it closed. Kept distinct from the state so "closed as completed" and "closed as abandoned"
   * can never collapse into one fact: one produced a signed agreement and the other did not.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "closure_reason", length = 32)
  private ClosureReason closureReason;

  @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Signer> signers = new ArrayList<>();

  @Transient private boolean isNew = true;

  protected Agreement() {
    // JPA
  }

  private Agreement(
      UUID id,
      String trackingReference,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      int termMonths,
      LocalDate startDate,
      LocalDate endDate,
      Instant createdAt) {
    this.id = id;
    this.trackingReference = trackingReference;
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
        TrackingReference.generate(),
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

  /**
   * Claim this agreement for {@code ownerIdentityId} (the save action). The state machine: an
   * <b>unowned</b> agreement becomes owned; a re-claim by the <b>same</b> owner is an idempotent
   * no-op; a claim by a <b>different</b> owner throws (the {@code api}/service layer maps that to
   * the same 404 as an unknown id, so claim is not an ownership oracle). Owner is server-sourced
   * from the authenticated principal -- never a client body field.
   *
   * @throws IllegalStateException if already owned by a different identity
   */
  void claimBy(UUID ownerIdentityId) {
    if (this.ownerIdentityId == null) {
      this.ownerIdentityId = ownerIdentityId;
      return;
    }
    if (!this.ownerIdentityId.equals(ownerIdentityId)) {
      throw new IllegalStateException("agreement already owned by another identity");
    }
    // same owner -> idempotent no-op
  }

  /**
   * Fully replace the mutable rental terms (edit). The party list is replaced separately via {@link
   * #clearSigners} + {@link #addSigner}. The term in months is re-derived from the new dates. Only
   * valid pre-signing-request (the service enforces the freeze); anti-mass-assignment fields (id,
   * owner, {@code createdAt}) are untouched.
   */
  void replaceTerms(
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      LocalDate startDate,
      LocalDate endDate) {
    this.propertyAddress = propertyAddress;
    this.monthlyRent = monthlyRent;
    this.securityDeposit = securityDeposit;
    this.startDate = startDate;
    this.endDate = endDate;
    this.termMonths = TenancyDuration.months(startDate, endDate);
  }

  /**
   * Remove all signers (orphan-removal deletes the child rows) -- for a wholesale party replace.
   */
  void clearSigners() {
    signers.clear();
  }

  /**
   * Clear the pinned draft: the stored-draft key and the effective-template reproducibility pin
   * ({@code contentHash} + resolved layer versions). Called on a successful edit so the next
   * generate-as-draft regenerates from the edited terms -- a stale draft is never served as if it
   * matched the new terms. Server-managed only.
   */
  void clearDraftPin() {
    this.draftPdfKey = null;
    this.templateContentHash = null;
    this.templateLayerVersions = null;
  }

  /** Attach (or replace) the uploaded e-stamp certificate data. Server-managed only. */
  void attachStamp(StampInfo stampInfo) {
    this.stampInfo = stampInfo;
  }

  /**
   * Record that this agreement has been paid for, through the vendor-neutral {@link
   * in.agreementmitra.signing.PaymentConfirmation} seam. Server-managed only - payment state is
   * never settable by a client on create or any other request. Every transition records who caused
   * it and when.
   */
  void recordPayment(in.agreementmitra.signing.PaymentConfirmation confirmation, UUID actorId) {
    this.paymentState = PaymentState.PAID;
    this.paymentAmount = confirmation.amount();
    this.paymentCurrency = confirmation.currency();
    this.paymentReference = confirmation.reference();
    this.paymentActorIdentityId = actorId;
    this.paymentRecordedAt = confirmation.confirmedAt();
  }

  /**
   * Record a deliberate decision to proceed without payment. Stays distinguishable from {@code
   * PAID} forever: no amount, no currency, no external reference is invented - only the actor and
   * the time, so a report can always tell a waiver from money received.
   */
  void waivePayment(UUID actorId, Instant at) {
    this.paymentState = PaymentState.WAIVED;
    this.paymentAmount = null;
    this.paymentCurrency = null;
    this.paymentReference = null;
    this.paymentActorIdentityId = actorId;
    this.paymentRecordedAt = at;
  }

  /**
   * Close the agreement: no work outstanding. Records <b>when</b> and <b>why</b>, and is
   * <b>idempotent and terminal</b> - a second close (the completion path is re-entered by both the
   * webhook and the reconciliation job) leaves the original reason and time untouched rather than
   * rewriting history, and a closed agreement never returns to {@code OPEN}.
   *
   * <p>Closure is not deletion and withdraws no access: the stored artifacts and records are
   * retained, and a party can still retrieve their signed agreement indefinitely afterwards.
   *
   * @return true if this call is the one that closed it
   */
  boolean close(ClosureReason reason, Instant at) {
    if (closureState == ClosureState.CLOSED) {
      return false; // already closed - keep the original reason and time
    }
    this.closureState = ClosureState.CLOSED;
    this.closureReason = reason;
    this.closedAt = at;
    return true;
  }

  /**
   * Replace the capture state <b>wholesale</b> (set on create, replaced on edit -- like the party
   * list). A null/empty {@code data} with null/empty {@code activeSections} collapses to a null
   * capture state, so an API client that sends only the fixed fields persists no capture state and
   * renders via the fixed-column fallback exactly as before. The {@link CaptureState} value object
   * defensively copies both collections. The caller (service) is responsible for stripping any
   * server-managed key from {@code data} first (anti-mass-assignment); the aggregate stores what it
   * is given.
   */
  void replaceCaptureState(Map<String, String> data, List<String> activeSections) {
    boolean noData = data == null || data.isEmpty();
    boolean noSections = activeSections == null || activeSections.isEmpty();
    this.captureState = noData && noSections ? null : new CaptureState(data, activeSections);
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

  /**
   * The agreement's single, immutable tracking reference (design D3) - the value shown to the
   * customer, quoted by staff at stamp intake, and rendered on the document. Never null after
   * creation.
   */
  String trackingReference() {
    return trackingReference;
  }

  UUID ownerIdentityId() {
    return ownerIdentityId;
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

  /**
   * The persisted capture state, or {@code null} when none was stored (legacy row /
   * fixed-fields-only API client). The returned {@link CaptureState} is immutable (its collections
   * are unmodifiable copies), so it is safe to hand out directly.
   */
  CaptureState captureState() {
    return captureState;
  }

  StampInfo stampInfo() {
    return stampInfo;
  }

  /** Never null: a new agreement starts {@link PaymentState#UNPAID}. */
  PaymentState paymentState() {
    return paymentState;
  }

  BigDecimal paymentAmount() {
    return paymentAmount;
  }

  String paymentCurrency() {
    return paymentCurrency;
  }

  String paymentReference() {
    return paymentReference;
  }

  Instant paymentRecordedAt() {
    return paymentRecordedAt;
  }

  /** Never null: a new agreement starts {@link ClosureState#OPEN}. */
  ClosureState closureState() {
    return closureState;
  }

  Instant closedAt() {
    return closedAt;
  }

  /** Null while open; distinguishes a completed close from an abandoned one once closed. */
  ClosureReason closureReason() {
    return closureReason;
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

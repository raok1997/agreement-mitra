package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.DocumentStatusView.InviteeStatusView;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignatureStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A signing request aggregate: one attempt to eSign a given agreement, and the home of the
 * signing-status FSM. The {@code Agreement} aggregate stays status-less (CR-2); the status lives
 * here. One agreement may have many signing requests over time.
 *
 * <p>Lifecycle for this CR: {@code PDF_GENERATED} (persisted before the provider call, so a
 * provider-success / DB-failure split never orphans a live document) → {@code SIGN_REQUESTED}
 * (after the provider returns a document id) → {@code SIGNED | FAILED | EXPIRED} (driven by the
 * webhook off authoritative provider status). Terminal states are idempotent; any other transition
 * is rejected. {@code @Version} makes concurrent/duplicate webhook deliveries safe.
 *
 * <p>Id is app-assigned in the factory (stable identity from birth); {@link Persistable} with a
 * transient {@code isNew} flag avoids a phantom {@code SELECT} before {@code INSERT}.
 */
@Entity
@Table(name = "signing_request")
class SigningRequest implements Persistable<UUID> {

  /** Legal forward transitions. Terminal-to-same-terminal is handled separately (idempotent). */
  private static final Map<SignatureStatus, Set<SignatureStatus>> ALLOWED =
      Map.of(
          SignatureStatus.PDF_GENERATED, EnumSet.of(SignatureStatus.SIGN_REQUESTED),
          SignatureStatus.SIGN_REQUESTED,
              EnumSet.of(SignatureStatus.SIGNED, SignatureStatus.FAILED, SignatureStatus.EXPIRED));

  private static final Set<SignatureStatus> TERMINAL =
      EnumSet.of(SignatureStatus.SIGNED, SignatureStatus.FAILED, SignatureStatus.EXPIRED);

  @Id private UUID id;

  @Column(name = "agreement_id", nullable = false)
  private UUID agreementId;

  @Column(name = "provider_document_id")
  private String providerDocumentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SignatureStatus status;

  @Version private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "signingRequest", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<SigningRequestInvitee> invitees = new ArrayList<>();

  /** Object-storage key for the signed PDF; null until the SIGNED transition stores it. */
  @Column(name = "signed_pdf_key")
  private String signedPdfKey;

  /** Object-storage key for the audit trail; null until the SIGNED transition stores it. */
  @Column(name = "audit_trail_key")
  private String auditTrailKey;

  @Transient private boolean isNew = true;

  protected SigningRequest() {
    // JPA
  }

  private SigningRequest(UUID id, UUID agreementId, SignatureStatus status, Instant createdAt) {
    this.id = id;
    this.agreementId = agreementId;
    this.status = status;
    this.createdAt = createdAt;
  }

  /**
   * Create a pre-request row (before the provider call) in {@link SignatureStatus#PDF_GENERATED}.
   */
  static SigningRequest createPending(UUID agreementId) {
    return new SigningRequest(
        UUID.randomUUID(), agreementId, SignatureStatus.PDF_GENERATED, Instant.now());
  }

  /**
   * Drive the FSM to {@code target}. Same-terminal is an idempotent no-op (safe under webhook
   * re-delivery); any non-legal transition throws.
   */
  void transitionTo(SignatureStatus target) {
    if (status == target && TERMINAL.contains(target)) {
      return; // idempotent terminal re-delivery
    }
    if (!ALLOWED.getOrDefault(status, Set.of()).contains(target)) {
      throw new IllegalStateException(
          "Illegal signing-request transition: " + status + " -> " + target);
    }
    this.status = target;
  }

  /** Record the provider document id + per-invitee URLs and move to {@code SIGN_REQUESTED}. */
  void markRequested(String providerDocumentId, List<SigningRequestInvitee> inviteeRows) {
    this.providerDocumentId = providerDocumentId;
    inviteeRows.forEach(this::addInvitee);
    transitionTo(SignatureStatus.SIGN_REQUESTED);
  }

  private void addInvitee(SigningRequestInvitee invitee) {
    invitee.attachTo(this);
    invitees.add(invitee);
  }

  /**
   * Apply the authoritative per-invitee statuses: persist each onto its matching invitee row (for
   * display, correlated by provider id with an ordinal fallback), then aggregate the multiset to a
   * terminal FSM state and transition. The terminal decision is computed from the view's status
   * counts (correlation-independent), so a correlation mismatch can never corrupt the FSM outcome.
   */
  void applyInviteeStatuses(DocumentStatusView view) {
    for (InviteeStatusView v : view.invitees()) {
      correlate(v).ifPresent(row -> row.updateStatus(v.status()));
    }
    aggregate(view).ifPresent(this::transitionTo);
  }

  /** Match a view entry to a persisted invitee row: provider id first, ordinal as fallback. */
  private Optional<SigningRequestInvitee> correlate(InviteeStatusView v) {
    if (v.providerInviteeId() != null) {
      Optional<SigningRequestInvitee> byId =
          invitees.stream()
              .filter(i -> v.providerInviteeId().equals(i.providerInviteeId()))
              .findFirst();
      if (byId.isPresent()) {
        return byId;
      }
    }
    return invitees.stream()
        .filter(i -> i.signingOrder() != null && i.signingOrder() == (short) v.ordinal())
        .findFirst();
  }

  /**
   * Aggregate the per-invitee statuses to a terminal FSM target, or empty for a no-op: any {@code
   * REJECTED} → {@code FAILED} (outranks expiry); else any {@code EXPIRED} → {@code EXPIRED}; else
   * all invitees {@code SIGNED} (and the view covers every invitee) → {@code SIGNED}; else empty.
   */
  private Optional<SignatureStatus> aggregate(DocumentStatusView view) {
    List<InviteeStatus> statuses = view.invitees().stream().map(InviteeStatusView::status).toList();
    if (statuses.isEmpty()) {
      return Optional.empty();
    }
    if (statuses.contains(InviteeStatus.REJECTED)) {
      return Optional.of(SignatureStatus.FAILED);
    }
    if (statuses.contains(InviteeStatus.EXPIRED)) {
      return Optional.of(SignatureStatus.EXPIRED);
    }
    boolean coversAll = statuses.size() >= invitees.size();
    if (coversAll && statuses.stream().allMatch(s -> s == InviteeStatus.SIGNED)) {
      return Optional.of(SignatureStatus.SIGNED);
    }
    return Optional.empty();
  }

  /**
   * True once SIGNED but artifacts not yet stored — the trigger for the download-on-SIGNED step.
   */
  boolean needsArtifacts() {
    return status == SignatureStatus.SIGNED && signedPdfKey == null;
  }

  /** Record the object-storage keys for the downloaded artifacts (the atomic completion pair). */
  void storeArtifactKeys(String signedPdfKey, String auditTrailKey) {
    this.signedPdfKey = signedPdfKey;
    this.auditTrailKey = auditTrailKey;
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

  UUID agreementId() {
    return agreementId;
  }

  String providerDocumentId() {
    return providerDocumentId;
  }

  SignatureStatus status() {
    return status;
  }

  Instant createdAt() {
    return createdAt;
  }

  List<SigningRequestInvitee> invitees() {
    return Collections.unmodifiableList(invitees);
  }

  String signedPdfKey() {
    return signedPdfKey;
  }

  String auditTrailKey() {
    return auditTrailKey;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof SigningRequest other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id + status only — never the document id, signer ids, or signing URLs.
    return "SigningRequest{id=" + id + ", status=" + status + "}";
  }
}

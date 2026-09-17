package in.agreementmitra.signing.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * The stamp quote frozen with one payment order (state-stamp-duty-quoting, design D7): the legal
 * duty, the stamp value the customer chose, the rule and catalog it was computed under, and -- only
 * for a below-duty choice -- the acknowledgement. Write-once: every column is {@code updatable =
 * false} and there is no mutator.
 *
 * <p>Carries no party name, contact or address. {@link #toString()} emits identifiers and amounts
 * only.
 *
 * <p>Java-{@code public} so the payment and signing-request packages can read it; still
 * Modulith-internal.
 */
@Entity
@Table(name = "stamp_quote")
public class StampQuoteRecord implements Persistable<UUID> {

  /** One breakdown line as stored: the calculator's kind, label and rupee amount (plain string). */
  public record Line(String kind, String label, String amount) {}

  @Id
  @Column(name = "payment_order_id", updatable = false)
  private UUID paymentOrderId;

  @Column(name = "agreement_id", nullable = false, updatable = false)
  private UUID agreementId;

  @Column(name = "duty_minor_units", nullable = false, updatable = false)
  private long dutyMinorUnits;

  @Column(name = "stamp_value_minor_units", nullable = false, updatable = false)
  private long stampValueMinorUnits;

  @Column(name = "below_duty", nullable = false, updatable = false)
  private boolean belowDuty;

  @Column(name = "medium_id", nullable = false, updatable = false, length = 64)
  private String mediumId;

  @Column(name = "rule_id", nullable = false, updatable = false, length = 128)
  private String ruleId;

  @Column(name = "rule_content_hash", nullable = false, updatable = false, length = 64)
  private String ruleContentHash;

  @Column(name = "rule_reviewed", nullable = false, updatable = false)
  private boolean ruleReviewed;

  @Column(name = "catalog_content_hash", nullable = false, updatable = false, length = 64)
  private String catalogContentHash;

  @Column(name = "execution_date", nullable = false, updatable = false)
  private LocalDate executionDate;

  @Column(name = "registration_required", nullable = false, updatable = false)
  private boolean registrationRequired;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "breakdown", nullable = false, updatable = false)
  private List<Line> breakdown;

  @Column(name = "ack_warning_version", updatable = false, length = 64)
  private String ackWarningVersion;

  @Column(name = "ack_identity_id", updatable = false)
  private UUID ackIdentityId;

  @Column(name = "ack_at", updatable = false)
  private Instant ackAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  protected StampQuoteRecord() {
    // JPA
  }

  /** The acknowledgement of a below-duty choice; null for any other choice. */
  public record Acknowledgement(String warningVersion, UUID identityId, Instant at) {}

  /** Everything a quote freezes, computed server-side at order creation. */
  public record Snapshot(
      long dutyMinorUnits,
      long stampValueMinorUnits,
      String mediumId,
      String ruleId,
      String ruleContentHash,
      boolean ruleReviewed,
      String catalogContentHash,
      LocalDate executionDate,
      boolean registrationRequired,
      List<Line> breakdown) {

    public boolean belowDuty() {
      return stampValueMinorUnits < dutyMinorUnits;
    }
  }

  /**
   * Freeze a quote for an order.
   *
   * @throws IllegalArgumentException when a below-duty choice lacks an acknowledgement or an
   *     at-or-above-duty choice carries one -- the same invariant the database enforces
   */
  public static StampQuoteRecord freeze(
      UUID paymentOrderId,
      UUID agreementId,
      Snapshot snapshot,
      Acknowledgement acknowledgement,
      Instant createdAt) {
    if (snapshot.belowDuty() != (acknowledgement != null)) {
      throw new IllegalArgumentException(
          "a below-duty stamp choice requires an acknowledgement, and only a below-duty one");
    }
    StampQuoteRecord record = new StampQuoteRecord();
    record.paymentOrderId = paymentOrderId;
    record.agreementId = agreementId;
    record.dutyMinorUnits = snapshot.dutyMinorUnits();
    record.stampValueMinorUnits = snapshot.stampValueMinorUnits();
    record.belowDuty = snapshot.belowDuty();
    record.mediumId = snapshot.mediumId();
    record.ruleId = snapshot.ruleId();
    record.ruleContentHash = snapshot.ruleContentHash();
    record.ruleReviewed = snapshot.ruleReviewed();
    record.catalogContentHash = snapshot.catalogContentHash();
    record.executionDate = snapshot.executionDate();
    record.registrationRequired = snapshot.registrationRequired();
    record.breakdown = List.copyOf(snapshot.breakdown());
    if (acknowledgement != null) {
      record.ackWarningVersion = acknowledgement.warningVersion();
      record.ackIdentityId = acknowledgement.identityId();
      record.ackAt = acknowledgement.at();
    }
    record.createdAt = createdAt;
    return record;
  }

  @Override
  public UUID getId() {
    return paymentOrderId;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @jakarta.persistence.PostLoad
  @jakarta.persistence.PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public UUID paymentOrderId() {
    return paymentOrderId;
  }

  public UUID agreementId() {
    return agreementId;
  }

  public long dutyMinorUnits() {
    return dutyMinorUnits;
  }

  public long stampValueMinorUnits() {
    return stampValueMinorUnits;
  }

  public boolean belowDuty() {
    return belowDuty;
  }

  public String mediumId() {
    return mediumId;
  }

  public String ruleId() {
    return ruleId;
  }

  public boolean ruleReviewed() {
    return ruleReviewed;
  }

  public LocalDate executionDate() {
    return executionDate;
  }

  public boolean registrationRequired() {
    return registrationRequired;
  }

  public List<Line> breakdown() {
    return breakdown == null ? List.of() : List.copyOf(breakdown);
  }

  public String ackWarningVersion() {
    return ackWarningVersion;
  }

  public Instant ackAt() {
    return ackAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** Identifiers and amounts only. */
  @Override
  public String toString() {
    return "StampQuoteRecord{paymentOrderId="
        + paymentOrderId
        + ", duty="
        + dutyMinorUnits
        + ", stampValue="
        + stampValueMinorUnits
        + ", belowDuty="
        + belowDuty
        + ", rule="
        + ruleId
        + "}";
  }
}

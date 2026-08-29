package in.agreementmitra.signing.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One order placed with the payment gateway for one agreement.
 *
 * <p>Its reason for existing is the <b>cross-check</b> (design D8): it records the amount and
 * currency we asked for, so a confirmation can be verified against them rather than believed. A
 * mismatch - a mis-created order, a tampered flow, a provider bug - would otherwise credit an
 * agreement for the wrong sum, and refusing loudly beats accepting silently.
 *
 * <p>Money is <b>integer minor units</b> (paise) here as everywhere else; there is no
 * floating-point type on this entity.
 *
 * <p>{@code version} gives optimistic locking so two concurrent webhook deliveries for one order
 * cannot both apply a confirmation. Id is app-assigned in the factory (equality stable from birth),
 * with the {@link Persistable} {@code isNew} flag so {@code save()} does not issue a phantom {@code
 * SELECT} before {@code INSERT}.
 *
 * <p>No card, UPI, or bank credential can be held here: there is deliberately no field that could
 * carry one. Razorpay Checkout collects those in its own context and they never reach our servers.
 */
@Entity
@Table(name = "payment_order")
class PaymentOrder implements Persistable<UUID> {

  /**
   * The only gateway integrated today. Stored so a second provider is an adapter, not a migration.
   */
  static final String PROVIDER_RAZORPAY = "razorpay";

  @Id private UUID id;

  @Column(name = "agreement_id", nullable = false, updatable = false)
  private UUID agreementId;

  @Column(name = "provider", nullable = false, updatable = false, length = 32)
  private String provider;

  @Column(name = "provider_order_id", nullable = false, updatable = false, length = 64)
  private String providerOrderId;

  @Column(name = "receipt", nullable = false, updatable = false, length = 40)
  private String receipt;

  @Column(name = "amount_minor_units", nullable = false, updatable = false)
  private long amountMinorUnits;

  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private PaymentOrderStatus status;

  @Column(name = "provider_payment_id", length = 64)
  private String providerPaymentId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Transient private boolean isNew = true;

  protected PaymentOrder() {
    // JPA
  }

  private PaymentOrder(
      UUID id,
      UUID agreementId,
      String provider,
      String providerOrderId,
      String receipt,
      Money amount,
      Instant createdAt) {
    this.id = id;
    this.agreementId = agreementId;
    this.provider = provider;
    this.providerOrderId = providerOrderId;
    this.receipt = receipt;
    this.amountMinorUnits = amount.minorUnits();
    this.currency = amount.currency();
    this.status = PaymentOrderStatus.CREATED;
    this.createdAt = createdAt;
  }

  static PaymentOrder create(
      UUID agreementId, String providerOrderId, String receipt, Money amount, Instant createdAt) {
    return new PaymentOrder(
        UUID.randomUUID(),
        agreementId,
        PROVIDER_RAZORPAY,
        providerOrderId,
        receipt,
        amount,
        createdAt);
  }

  /**
   * Record the confirmed payment. Idempotent by construction: an order that is already {@code PAID}
   * keeps its original payment id and confirmation time, so a redelivered webhook or a second event
   * for the same payment changes nothing.
   */
  void markPaid(String providerPaymentId, Instant confirmedAt) {
    if (status.settled()) {
      return;
    }
    this.status = PaymentOrderStatus.PAID;
    this.providerPaymentId = providerPaymentId;
    this.confirmedAt = confirmedAt;
  }

  /** The provider reports this order failed. Never applied to a settled order (no going back). */
  void markFailed() {
    if (!status.settled()) {
      this.status = PaymentOrderStatus.FAILED;
    }
  }

  /** Outstanding past its useful life. Never applied to a settled order (no going back). */
  void markExpired() {
    if (!status.settled()) {
      this.status = PaymentOrderStatus.EXPIRED;
    }
  }

  /** Whether this order has been outstanding longer than {@code ttl} as at {@code now}. */
  boolean outstandingLongerThan(java.time.Duration ttl, Instant now) {
    return status.outstanding() && createdAt.plus(ttl).isBefore(now);
  }

  /** The amount and currency this order was placed for - what a confirmation is checked against. */
  Money amount() {
    return new Money(amountMinorUnits, currency);
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

  String providerOrderId() {
    return providerOrderId;
  }

  String receipt() {
    return receipt;
  }

  long amountMinorUnits() {
    return amountMinorUnits;
  }

  String currency() {
    return currency;
  }

  PaymentOrderStatus status() {
    return status;
  }

  String providerPaymentId() {
    return providerPaymentId;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant confirmedAt() {
    return confirmedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof PaymentOrder other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  /**
   * Id and status only - never the provider identifiers, which are redacted wherever they appear.
   */
  @Override
  public String toString() {
    return "PaymentOrder{id=" + id + ", status=" + status + "}";
  }
}

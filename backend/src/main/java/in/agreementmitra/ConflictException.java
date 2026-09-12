package in.agreementmitra;

import java.util.Collection;
import java.util.List;

/**
 * Thrown when a request conflicts with the resource's current state; mapped by {@link
 * GlobalExceptionHandler} to a 409 ProblemDetail. Lives in the root package (not a Spring Modulith
 * module) so the cross-cutting handler can reference it without reaching into any module's
 * internals.
 *
 * <p>Carries a {@link Kind} rather than client-facing text: the handler renders a fixed per-kind
 * {@code type}/{@code title}/{@code detail} constant, so no client text is ever derived from input.
 * The message is for redacted server-side logging only and MUST NOT reach the body.
 */
public class ConflictException extends RuntimeException {

  /**
   * The specific conflict, so the handler can emit a distinct problem {@code type} URN per case.
   */
  public enum Kind {
    /** A draft upload was attempted after signing was already requested (draft finalized). */
    DRAFT_FROZEN,
    /** Signing was requested for an agreement that has no uploaded draft. */
    DRAFT_REQUIRED,
    /**
     * A party is not reachable on any <b>enabled delivery channel</b>. Raised at order creation
     * (before money moves) and again at signing initiation as defence in depth - one rule, from
     * {@code PartyReachability}, applied at both gates.
     */
    CONTACT_REQUIRED,
    /** Signing was requested but the drafted artifact yields no eSign anchor (nothing signable). */
    NOT_SIGNABLE,
    /** Signing was requested for an agreement with no e-stamp attached yet (staff upload owed). */
    STAMP_REQUIRED,
    /** A stamp upload targeted an agreement that already has one, or is no longer awaiting one. */
    STAMP_ALREADY_ATTACHED,
    /** A stamp upload carried a certificate number already spent on some agreement (single-use). */
    CERTIFICATE_ALREADY_USED,
    /** A staff action targeted an agreement the customer has not finalised, so no order exists. */
    ORDER_NOT_PLACED,
    /** A gated step was attempted for an unpaid agreement while the payment gate is REQUIRED. */
    PAYMENT_REQUIRED,
    /**
     * A paid-fulfilment step was attempted for an agreement whose <b>duty jurisdiction</b> is not
     * one we can fulfil. Stamp duty is state law, so such an agreement has no computable duty and
     * no defined state in which staff could buy the certificate.
     *
     * <p>Deliberately <b>not</b> folded into {@link #PAYMENT_REQUIRED}, for the reason {@code
     * PaymentGate} already gives about its own refusal: a different person fixes each one. An
     * unpaid order is fixed by the customer paying; an unsupported jurisdiction is fixed by product
     * deciding to support that state.
     *
     * <p>Raised at order placement, checkout, e-stamp intake and eSign initiation - every step that
     * commits us to something real in a jurisdiction.
     */
    JURISDICTION_UNSUPPORTED,
    /** An external payment reference already recorded against some agreement was reused. */
    PAYMENT_REFERENCE_ALREADY_USED,
    /** A fulfilment action was attempted on an agreement that has already been closed. */
    AGREEMENT_CLOSED,
    /**
     * A contacts change was attempted on an agreement whose payment is already settled (paid or
     * waived), so the details are frozen.
     */
    CONTACTS_FROZEN
  }

  private final Kind kind;

  /** Safe, structured context for the client: role-and-position labels only. Never PII. */
  private final List<String> partyLabels;

  /**
   * The rejected jurisdiction code, server-derived from the agreement's pinned template; null when
   * none could be resolved, and for every kind but {@link Kind#JURISDICTION_UNSUPPORTED}.
   */
  private final String rejectedJurisdiction;

  /** The jurisdictions that would have been accepted. Public by construction, never PII. */
  private final List<String> eligibleJurisdictions;

  private ConflictException(Kind kind, String message) {
    this(kind, message, List.of());
  }

  private ConflictException(Kind kind, String message, List<String> partyLabels) {
    this(kind, message, partyLabels, null, List.of());
  }

  private ConflictException(
      Kind kind,
      String message,
      List<String> partyLabels,
      String rejectedJurisdiction,
      List<String> eligibleJurisdictions) {
    super(message);
    this.kind = kind;
    this.partyLabels = partyLabels;
    this.rejectedJurisdiction = rejectedJurisdiction;
    this.eligibleJurisdictions = eligibleJurisdictions;
  }

  public static ConflictException draftFrozen() {
    return new ConflictException(Kind.DRAFT_FROZEN, "draft frozen: signing already requested");
  }

  public static ConflictException draftRequired() {
    return new ConflictException(Kind.DRAFT_REQUIRED, "draft required before signing");
  }

  public static ConflictException contactRequired() {
    return new ConflictException(Kind.CONTACT_REQUIRED, "contact required before signing");
  }

  /**
   * The same conflict, carrying which parties are unreachable so the customer can fix the right
   * one.
   *
   * <p>Same {@link Kind} - and therefore the same problem type - as {@link #contactRequired()} on
   * purpose: it is one condition with one remedy, and splitting it would make a client handle two
   * types that mean the same thing.
   *
   * <p><b>Labels, not a message.</b> The handler's contract is that client-facing {@code detail} is
   * a fixed constant and never derived from an exception message, because messages have a habit of
   * accumulating rejected values. These labels are carried as structured data instead, and they are
   * safe by construction: a party is identified only by role and position ("tenant 1"), never by
   * name, email, or number.
   *
   * @param partyLabels role-and-position labels for the unreachable parties
   */
  public static ConflictException contactRequired(List<String> partyLabels) {
    return new ConflictException(
        Kind.CONTACT_REQUIRED, "contact required before payment", List.copyOf(partyLabels));
  }

  /** Role-and-position labels for the parties this conflict is about; empty when not applicable. */
  public List<String> partyLabels() {
    return partyLabels;
  }

  /**
   * The agreement's duty jurisdiction is not one we can fulfil, so no paid-fulfilment step may
   * proceed.
   *
   * <p><b>Structured context, not a message</b> - the same discipline as {@link
   * #contactRequired(List)}. Both values are safe by construction because both are
   * <b>server-derived</b>: the rejected code comes from the agreement's pinned template, never from
   * the request, and the eligible list is public by construction (the template picker and the
   * published terms both disclose it). So naming them makes the refusal actionable without
   * violating the never-echo-input contract.
   *
   * @param rejected the agreement's jurisdiction code, or {@code null} where none could be resolved
   *     at all (no pinned template, or one that no longer resolves)
   * @param eligible the jurisdictions that would have been accepted
   */
  public static ConflictException jurisdictionUnsupported(
      String rejected, Collection<String> eligible) {
    return new ConflictException(
        Kind.JURISDICTION_UNSUPPORTED,
        "jurisdiction not eligible for paid fulfilment",
        List.of(),
        rejected,
        List.copyOf(eligible));
  }

  /**
   * The agreement's jurisdiction code, or {@code null} when none could be resolved. Only meaningful
   * for {@link Kind#JURISDICTION_UNSUPPORTED}.
   */
  public String rejectedJurisdiction() {
    return rejectedJurisdiction;
  }

  /**
   * The jurisdictions that would have been accepted; empty when not applicable. Only meaningful for
   * {@link Kind#JURISDICTION_UNSUPPORTED}.
   */
  public List<String> eligibleJurisdictions() {
    return eligibleJurisdictions;
  }

  public static ConflictException notSignable() {
    return new ConflictException(Kind.NOT_SIGNABLE, "no eSign anchor: artifact is not signable");
  }

  /**
   * Signing cannot start until staff have uploaded a purchased e-stamp. Distinct from {@link
   * #draftRequired()} and {@link #contactRequired()} so an operator can see WHICH precondition is
   * missing - the whole point of separating them is that a different person fixes each one.
   */
  public static ConflictException stampRequired() {
    return new ConflictException(Kind.STAMP_REQUIRED, "e-stamp required before signing");
  }

  /** The agreement already carries a stamp (or is past the awaiting-stamp state). */
  public static ConflictException stampAlreadyAttached() {
    return new ConflictException(
        Kind.STAMP_ALREADY_ATTACHED, "agreement is not awaiting a stamp upload");
  }

  /**
   * The certificate number is already recorded against some agreement. Raised from catching the
   * database's unique-constraint violation, never a read-then-write pre-check - two staff uploading
   * the same certificate concurrently must not both succeed, because that would spend one purchased
   * stamp on two instruments. The message names no agreement; nor may the response body.
   */
  public static ConflictException certificateAlreadyUsed() {
    return new ConflictException(
        Kind.CERTIFICATE_ALREADY_USED, "certificate number already spent on an agreement");
  }

  /**
   * The agreement has not been finalised, so there is no order to work on. Distinct from {@link
   * #stampAlreadyAttached()}: "too early" and "too late" need different fixes, and a staff member
   * staring at a 409 deserves to know which one they are looking at.
   */
  public static ConflictException orderNotPlaced() {
    return new ConflictException(Kind.ORDER_NOT_PLACED, "agreement has not been finalised");
  }

  /**
   * The payment gate is {@code REQUIRED} and the agreement is not {@code PAID}/{@code WAIVED}.
   * Distinct from {@link #draftRequired()}, {@link #stampRequired()} and {@link #contactRequired()}
   * so an operator can tell WHY the pipeline stopped - a different person fixes each, and "nobody
   * paid" is not a document problem.
   */
  public static ConflictException paymentRequired() {
    return new ConflictException(Kind.PAYMENT_REQUIRED, "payment required before this step");
  }

  /**
   * The external payment reference is already recorded against some agreement. Raised from catching
   * the database's unique-constraint violation, never a read-then-write pre-check - two concurrent
   * confirmations of the same payment must not both succeed, because that would credit one payment
   * to two agreements. The message names no agreement; nor may the response body.
   */
  public static ConflictException paymentReferenceAlreadyUsed() {
    return new ConflictException(
        Kind.PAYMENT_REFERENCE_ALREADY_USED, "payment reference already recorded");
  }

  /**
   * The agreement has closed, so no further fulfilment action may be taken on it. {@code CLOSED} is
   * terminal - a closed agreement never returns to an in-progress state, and reopening one for
   * revision is deliberately out of scope (a superseding agreement is a separate instrument).
   * Distinct from every other kind so an operator can see that the work is <em>finished or
   * abandoned</em>, not blocked on a missing document, stamp or payment.
   */
  public static ConflictException agreementClosed() {
    return new ConflictException(Kind.AGREEMENT_CLOSED, "agreement is closed");
  }

  /**
   * Contacts can no longer be changed because the agreement's payment is settled - paid, or waived
   * by staff.
   *
   * <p>Deliberately <b>not</b> {@link #draftFrozen()}. That one is about the TERMS, which freeze
   * when the order is placed; contacts stay editable past that point precisely because they are not
   * terms and do not appear in the rendered agreement. Sharing a kind would have left a client
   * unable to tell "your rent is locked" from "your email is locked", and the observed cost of that
   * was a client inviting a retry of something that can never succeed.
   *
   * <p>Distinct from {@link #agreementClosed()} too: a closed agreement is finished or abandoned,
   * whereas this one is alive and moving through fulfilment. Different fact, different remedy.
   */
  public static ConflictException contactsFrozen() {
    return new ConflictException(
        Kind.CONTACTS_FROZEN, "contacts frozen: payment already settled for this agreement");
  }

  public Kind kind() {
    return kind;
  }
}

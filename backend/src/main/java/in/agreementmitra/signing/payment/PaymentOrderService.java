package in.agreementmitra.signing.payment;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.CheckoutCallbackRequest;
import in.agreementmitra.signing.api.CheckoutSessionResponse;
import in.agreementmitra.signing.api.PaymentProgressResponse;
import in.agreementmitra.signing.contact.PartyReachability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Gateway-backed payment for one agreement: place a priced order, report progress, and apply
 * confirmations.
 *
 * <p><b>The webhook is authoritative; the browser callback is not</b> (design D1). This class marks
 * an agreement paid on exactly two grounds - a verified webhook, or an authoritative read of the
 * order from the provider. It never does so on the strength of what the browser reports, because
 * the value the checkout handler returns travels through the user's browser: it can be withheld
 * (tab closed), replayed, or tampered with. Verifying its signature proves the provider
 * <em>issued</em> it, not that it is current or that it arrived at all. The failure that matters is
 * the ordinary one - a customer pays and closes the tab - and if the callback were authoritative
 * they would be charged and stay {@code UNPAID}.
 *
 * <p><b>Pricing is ours.</b> The amount comes from {@link PaymentPricing} and is integer minor
 * units throughout. No request on this surface has an amount, currency, or discount field.
 *
 * <p>Java-{@code public} so the {@code api} controllers can call it; still Modulith-internal.
 */
@Service
public class PaymentOrderService {

  private static final Logger log = LoggerFactory.getLogger(PaymentOrderService.class);

  /**
   * The provider caps {@code receipt} at 40 characters. An agreement UUID is 36, leaving room for a
   * short retry suffix and no more - so retries are bounded rather than silently truncating into a
   * collision.
   */
  private static final int MAX_ORDER_ATTEMPTS = 999;

  private final PaymentOrderRepository orders;
  private final PaymentPricing pricing;
  private final RazorpayClient razorpay;
  private final PaymentConfirmations confirmations;
  private final AgreementService agreementService;
  private final PaymentProperties properties;
  private final PartyReachability reachability;

  PaymentOrderService(
      PaymentOrderRepository orders,
      PaymentPricing pricing,
      RazorpayClient razorpay,
      PaymentConfirmations confirmations,
      AgreementService agreementService,
      PaymentProperties properties,
      PartyReachability reachability) {
    this.reachability = reachability;
    this.orders = orders;
    this.pricing = pricing;
    this.razorpay = razorpay;
    this.confirmations = confirmations;
    this.agreementService = agreementService;
    this.properties = properties;
  }

  /**
   * Refuse checkout unless <b>every</b> party is reachable on an enabled delivery channel.
   *
   * <p>This is the enforcement. The pre-checkout confirmation step in the SPA exists so customers
   * meet this before they hit it, but a step in a browser enforces nothing: a caller going straight
   * to the API must be refused here or the guarantee is decorative.
   *
   * <p>The refusal names parties by <b>role and position</b> - "owner 1", "tenant 2" - never by
   * name or contact value. The caller already holds the agreement, so this discloses nothing new,
   * and it keeps party PII out of an error body that may be logged by a client.
   */
  private void requireReachableParties(UUID agreementId) {
    AgreementResponse agreement =
        agreementService
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    Map<Role, Integer> seenPerRole = new EnumMap<>(Role.class);
    List<String> unreachable = new ArrayList<>();
    for (AgreementResponse.SignerResponse signer : agreement.signers()) {
      Role role = signer.role();
      int position = seenPerRole.merge(role, 1, Integer::sum);
      if (!reachability.isReachable(signer.email(), signer.mobile())) {
        String label = role == null ? "party" : role.name().toLowerCase(Locale.ROOT);
        unreachable.add(label + " " + position);
      }
    }
    if (!unreachable.isEmpty()) {
      throw ConflictException.contactRequired(unreachable);
    }
  }

  // --- order creation --------------------------------------------------------

  /**
   * Start (or resume) checkout for an agreement.
   *
   * <p><b>Idempotent per agreement</b> (design D5): an outstanding unpaid order is reused rather
   * than replaced, because customers reload payment pages. Without this, one agreement accumulates
   * orders and reconciliation then has to decide which one counts. A new order becomes legal only
   * once the previous has been outstanding past its useful life, or the provider has reported it
   * failed.
   *
   * @param agreementId the agreement to pay for
   * @param callerIdentityId the authenticated caller, or null when anonymous
   * @param staffCaller whether the caller holds the STAFF role (authorized before ownership)
   * @throws ResourceNotFoundException when the agreement is unknown, or the caller may not act on
   *     it - the same 404 either way, so ownership cannot be probed
   */
  public CheckoutSessionResponse startCheckout(
      UUID agreementId, UUID callerIdentityId, boolean staffCaller) {
    authorize(agreementId, callerIdentityId, staffCaller);

    // Every party must be reachable before money moves. Checked ahead of order reuse as well as
    // creation: an order placed earlier proves the parties were reachable then, not now, and an
    // edit since could have removed the only contact we had.
    requireReachableParties(agreementId);
    // NOTE: contact details are never read from the request here. They are agreement data, fixed
    // through the agreement, so a checkout call cannot smuggle in a contact to satisfy its own
    // gate - nor redirect where anything is later sent.

    PaymentOrder reusable = reusableOrder(agreementId);
    if (reusable != null) {
      return toSession(reusable);
    }
    Money price = pricing.priceFor(agreementId);
    String receipt = nextReceipt(agreementId);
    RazorpayClient.ProviderOrder placed = razorpay.createOrder(receipt, price);
    try {
      return toSession(
          orders.save(
              PaymentOrder.create(agreementId, placed.id(), receipt, price, Instant.now())));
    } catch (DataIntegrityViolationException raced) {
      // Two simultaneous starts for one agreement. The partial unique index on outstanding orders
      // is
      // what makes the reuse rule hold under a double-submit rather than merely usually hold:
      // whoever lost the race simply resumes the order that won.
      log.debug("Concurrent checkout start resolved to the existing outstanding order");
      return toSession(
          orders.findTopByAgreementIdOrderByCreatedAtDesc(agreementId).orElseThrow(() -> raced));
    }
  }

  /**
   * The latest order for the agreement if it should be resumed, else null. Expires an abandoned
   * outstanding order on the way past, so the next call may legitimately place a new one.
   */
  PaymentOrder reusableOrder(UUID agreementId) {
    PaymentOrder latest = orders.findTopByAgreementIdOrderByCreatedAtDesc(agreementId).orElse(null);
    if (latest == null) {
      return null;
    }
    if (latest.status().settled()) {
      return latest; // already paid - report it rather than opening checkout again
    }
    if (!latest.status().outstanding()) {
      return null; // FAILED / EXPIRED - a new order is legal
    }
    if (latest.outstandingLongerThan(properties.order().ttl(), Instant.now())) {
      latest.markExpired();
      orders.save(latest);
      return null;
    }
    return latest; // the customer reloaded - send them back to the same order
  }

  /**
   * The receipt for the next order: the agreement UUID, plus a {@code -N} suffix on a retry. The
   * prefix stays the agreement's own identifier, so the provider's record joins back to ours
   * without trusting any client-held value, and 36 + 4 characters stays inside the 40-character
   * limit.
   */
  private String nextReceipt(UUID agreementId) {
    long attempts = orders.countByAgreementId(agreementId);
    if (attempts == 0) {
      return agreementId.toString();
    }
    if (attempts >= MAX_ORDER_ATTEMPTS) {
      throw new IllegalStateException("too many payment attempts for this agreement");
    }
    return agreementId + "-" + (attempts + 1);
  }

  // --- progress --------------------------------------------------------------

  /**
   * Where payment stands. Polled by the SPA after the checkout modal closes, because the modal
   * closing establishes nothing - only a verified webhook or an authoritative read does.
   */
  public PaymentProgressResponse progress(
      UUID agreementId, UUID callerIdentityId, boolean staffCaller) {
    authorize(agreementId, callerIdentityId, staffCaller);
    PaymentOrder latest = orders.findTopByAgreementIdOrderByCreatedAtDesc(agreementId).orElse(null);
    return new PaymentProgressResponse(
        agreementId,
        paymentStateOf(agreementId).name(),
        latest == null ? null : latest.status().name(),
        latest == null ? null : latest.amountMinorUnits(),
        latest == null ? null : latest.currency());
  }

  // --- the browser callback (a UX signal, never a confirmation) --------------

  /**
   * Handle what the browser reports when the hosted checkout closes.
   *
   * <p>The handler signature is verified server-side ({@code order_id|payment_id} under the
   * <b>key</b> secret, not the webhook secret) - but a valid signature only earns the right to
   * <em>ask the provider</em>. It never marks the agreement paid on its own. The authoritative read
   * that follows is what decides, and it goes through the same confirmation path as the webhook.
   *
   * <p>An invalid signature is not an error the customer needs to see: the response is the current
   * payment progress either way, so a tampered callback simply learns nothing.
   */
  public PaymentProgressResponse acknowledgeCheckout(
      UUID agreementId,
      CheckoutCallbackRequest request,
      UUID callerIdentityId,
      boolean staffCaller) {
    authorize(agreementId, callerIdentityId, staffCaller);
    boolean issuedByProvider =
        RazorpaySignatures.handlerSignatureValid(
            request.razorpayOrderId(),
            request.razorpayPaymentId(),
            request.razorpaySignature(),
            razorpay.keySecret());
    if (!issuedByProvider) {
      log.warn("Checkout callback rejected: handler signature did not verify");
      return progress(agreementId, callerIdentityId, staffCaller);
    }
    // Verified means "the provider issued this", nothing more. Ask the provider what is actually
    // true before changing anything.
    readAuthoritatively(agreementId, request.razorpayOrderId());
    return progress(agreementId, callerIdentityId, staffCaller);
  }

  /**
   * Re-read one order from the provider and apply a confirmation if - and only if - the provider
   * itself reports it paid and captured. Shared by the browser-callback path and reconciliation, so
   * neither can invent a payment the provider does not acknowledge.
   *
   * <p>The order must be one of ours: a caller cannot name an arbitrary provider order and have it
   * credited somewhere.
   */
  ConfirmationOutcome readAuthoritatively(UUID agreementId, String providerOrderId) {
    Optional<PaymentOrder> ours = orders.findByProviderOrderId(providerOrderId);
    if (ours.isEmpty() || (agreementId != null && !ours.get().agreementId().equals(agreementId))) {
      return ConfirmationOutcome.UNKNOWN_ORDER;
    }
    return readAuthoritatively(providerOrderId);
  }

  /** As above, with the order already known to be ours (the reconciliation entry point). */
  ConfirmationOutcome readAuthoritatively(String providerOrderId) {
    Optional<RazorpayClient.ProviderOrder> providerOrder = razorpay.fetchOrder(providerOrderId);
    if (providerOrder.isEmpty() || !providerOrder.get().paid()) {
      // Unpaid, failed, expired, or simply unreachable: never a confirmation.
      return ConfirmationOutcome.UNKNOWN_ORDER;
    }
    Optional<RazorpayClient.ProviderPayment> captured =
        razorpay.capturedPaymentFor(providerOrderId);
    if (captured.isEmpty()) {
      return ConfirmationOutcome.MISSING_REFERENCE;
    }
    RazorpayClient.ProviderPayment payment = captured.get();
    return applyConfirmation(
        providerOrderId, payment.id(), payment.amountMinorUnits(), payment.currency());
  }

  // --- confirmation (the single write path) ----------------------------------

  /**
   * Apply a confirmation. The webhook and the reconciliation job both call exactly this, so there
   * is one set of idempotency rules rather than two that drift apart (design D9).
   *
   * <p>The duplicate-reference case is caught here, <b>outside</b> the transaction that provoked
   * it: the database's unique index on the external reference is what stops one payment being
   * credited to two agreements, and it is caught rather than pre-checked so two concurrent
   * confirmations cannot both win.
   */
  ConfirmationOutcome applyConfirmation(
      String providerOrderId,
      String providerPaymentId,
      long reportedMinorUnits,
      String reportedCurrency) {
    try {
      return confirmations.apply(
          providerOrderId, providerPaymentId, reportedMinorUnits, reportedCurrency);
    } catch (DataIntegrityViolationException alreadyRecorded) {
      log.warn(
          "Payment confirmation refused: the provider payment for order {} is already recorded",
          RazorpayClient.redact(providerOrderId));
      return ConfirmationOutcome.DUPLICATE_REFERENCE;
    }
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * STAFF may act on any agreement; anyone else must satisfy the same owner-scoping rule the rest
   * of the agreement surface uses (unowned = reachable by id, claimed = owner only). An unknown
   * agreement and an inaccessible one both raise the same 404.
   */
  private void authorize(UUID agreementId, UUID callerIdentityId, boolean staffCaller) {
    boolean permitted =
        staffCaller
            ? agreementService.paymentState(agreementId).isPresent()
            : agreementService.isAccessibleBy(agreementId, callerIdentityId);
    if (!permitted) {
      throw new ResourceNotFoundException("Agreement not found: " + agreementId);
    }
  }

  private PaymentState paymentStateOf(UUID agreementId) {
    return agreementService.paymentState(agreementId).orElse(PaymentState.UNPAID);
  }

  private CheckoutSessionResponse toSession(PaymentOrder order) {
    return new CheckoutSessionResponse(
        order.agreementId(),
        razorpay.publicKeyId(),
        order.providerOrderId(),
        order.amountMinorUnits(),
        order.currency(),
        order.status().name(),
        paymentStateOf(order.agreementId()).name());
  }
}

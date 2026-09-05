package in.agreementmitra.signing.api;

import in.agreementmitra.signing.payment.PaymentOrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer-facing payment surface: start checkout, report what the browser saw, and read where
 * payment stands.
 *
 * <p><b>Authorization is here, not in the filter chain</b> - deliberately, and for the same reason
 * {@code GET /api/agreements/{id}} decides ownership in its handler: the chain cannot see who owns
 * the row. STAFF may act on any agreement; anyone else must satisfy the same owner-scoping rule as
 * the rest of the agreement surface (an unowned draft is reachable by anyone holding its
 * unguessable id, which is what keeps the anonymous draft-and-finalise flow working; a claimed one
 * only by its owner). An unknown agreement and an inaccessible one both return the same 404, so
 * ownership can never be probed.
 *
 * <p><b>Nothing here accepts an amount.</b> Starting checkout takes no body at all; the callback
 * carries only identifiers and a signature. The amount is the server's calculation, so a tampered
 * client cannot change what is charged or what is credited. No field on this surface could carry a
 * card, UPI, netbanking, or wallet credential - the provider's hosted checkout collects those in
 * its own context and they never reach us.
 *
 * <p><b>The callback is not a confirmation.</b> {@code POST .../payment/callback} verifies the
 * handler signature and may trigger an authoritative read of the order, but it never marks the
 * agreement paid by itself. Payment is settled by a verified webhook or by that authoritative read
 * - a customer who pays and closes the tab must still end up {@code PAID}.
 */
@RestController
@RequestMapping("/api/agreements/{agreementId}/payment")
public class PaymentCheckoutController {

  private static final String STAFF_AUTHORITY = "ROLE_STAFF";

  private final PaymentOrderService paymentOrderService;

  public PaymentCheckoutController(PaymentOrderService paymentOrderService) {
    this.paymentOrderService = paymentOrderService;
  }

  /**
   * Place (or resume) the order and return what the browser needs to open checkout: the provider's
   * <b>public</b> key id, the order id, the amount in minor units, and the currency. No secret is
   * returned, and none could be - the response record has no field for one.
   *
   * <p>Idempotent: reloading the payment page resumes the outstanding order rather than
   * accumulating a new one for every refresh.
   */
  @PostMapping("/order")
  public CheckoutSessionResponse startCheckout(
      @PathVariable UUID agreementId,
      @AuthenticationPrincipal UUID identityId,
      Authentication authentication) {
    return paymentOrderService.startCheckout(agreementId, identityId, isStaff(authentication));
  }

  /** Where payment stands. Polled by the SPA once the checkout modal closes. */
  @GetMapping
  public PaymentProgressResponse progress(
      @PathVariable UUID agreementId,
      @AuthenticationPrincipal UUID identityId,
      Authentication authentication) {
    return paymentOrderService.progress(agreementId, identityId, isStaff(authentication));
  }

  /**
   * What the browser reports when checkout closes. A user-experience signal: the signature is
   * verified server-side and may trigger an authoritative read, but this endpoint cannot mark the
   * agreement paid. The response is the current payment progress either way, so a tampered callback
   * learns nothing.
   */
  @PostMapping("/callback")
  public PaymentProgressResponse callback(
      @PathVariable UUID agreementId,
      @Valid @RequestBody CheckoutCallbackRequest request,
      @AuthenticationPrincipal UUID identityId,
      Authentication authentication) {
    return paymentOrderService.acknowledgeCheckout(
        agreementId, request, identityId, isStaff(authentication));
  }

  /**
   * Whether the caller holds the STAFF role. Read from the authenticated principal's authorities,
   * which the session filter sets from the identity record - no client value contributes to it.
   */
  private static boolean isStaff(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(STAFF_AUTHORITY::equals);
  }
}

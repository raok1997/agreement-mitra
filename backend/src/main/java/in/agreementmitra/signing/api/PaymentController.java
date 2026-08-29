package in.agreementmitra.signing.api;

import in.agreementmitra.signing.payment.PaymentGate;
import in.agreementmitra.signing.payment.PaymentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff payment surface: record a manual payment confirmation, waive payment, read an
 * agreement's payment state, and see whether the gate is actually being enforced.
 *
 * <p><b>Authorization lives in the filter chain, not here.</b> {@code SecurityConfig} pins these
 * exact routes to the STAFF role, so an unauthenticated caller gets 401 and a non-staff caller gets
 * 403 <em>before this handler is ever entered</em> - no agreement lookup happens on a refused
 * request, so the endpoint cannot be used as an existence oracle.
 *
 * <p>No payment gateway is integrated by this change: there is no redirect, no card or UPI field,
 * and no gateway credential anywhere in this surface. A gateway-backed confirmation would arrive as
 * a second implementation behind the same {@code PaymentConfirmation} seam, and this manual path
 * would remain - a payment taken out of band still has to be recordable.
 */
@RestController
@RequestMapping("/api/staff/payments")
public class PaymentController {

  private final PaymentService paymentService;
  private final PaymentGate paymentGate;

  public PaymentController(PaymentService paymentService, PaymentGate paymentGate) {
    this.paymentService = paymentService;
    this.paymentGate = paymentGate;
  }

  /**
   * The active gate mode, so an operator can tell at runtime whether payment is being enforced
   * without reading configuration files.
   */
  @GetMapping("/gate")
  public Map<String, String> gate() {
    return Map.of("mode", paymentGate.mode().name());
  }

  /** One agreement's payment state. {@code PAID} and {@code WAIVED} are reported distinctly. */
  @GetMapping("/{agreementId}")
  public PaymentStateResponse state(@PathVariable UUID agreementId) {
    return paymentService.view(agreementId);
  }

  /**
   * Record a manual payment confirmation. The actor is the authenticated STAFF principal, never a
   * body field. A reference already recorded against another agreement is a 409.
   */
  @PostMapping("/{agreementId}/confirm")
  public PaymentStateResponse confirm(
      @AuthenticationPrincipal UUID staffIdentityId,
      @PathVariable UUID agreementId,
      @Valid @RequestBody PaymentConfirmRequest request) {
    return paymentService.confirm(
        staffIdentityId, agreementId, request.amount(), request.currency(), request.reference());
  }

  /** Waive payment: proceed deliberately without money, recorded distinctly from a real payment. */
  @PostMapping("/{agreementId}/waive")
  public PaymentStateResponse waive(
      @AuthenticationPrincipal UUID staffIdentityId, @PathVariable UUID agreementId) {
    return paymentService.waive(staffIdentityId, agreementId);
  }
}

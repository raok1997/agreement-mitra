package in.agreementmitra.signing.api;

import in.agreementmitra.signing.payment.PaymentOrderService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stamp quote shown before payment (state-stamp-duty-quoting). Owner-scoped exactly like the
 * payment surface: an unknown agreement and an inaccessible one both 404, so ownership cannot be
 * probed and no amount is disclosed to a caller who may not see the payment.
 */
@RestController
public class StampQuoteController {

  private static final String STAFF_AUTHORITY = "ROLE_STAFF";

  private final PaymentOrderService paymentOrderService;

  public StampQuoteController(PaymentOrderService paymentOrderService) {
    this.paymentOrderService = paymentOrderService;
  }

  @GetMapping("/api/agreements/{agreementId}/stamp-quote")
  public StampQuoteResponse stampQuote(
      @PathVariable UUID agreementId,
      @AuthenticationPrincipal UUID identityId,
      Authentication authentication) {
    return paymentOrderService.stampQuote(agreementId, identityId, isStaff(authentication));
  }

  private static boolean isStaff(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(STAFF_AUTHORITY::equals);
  }
}

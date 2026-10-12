package in.agreementmitra.signing.api;

import in.agreementmitra.signing.payment.PaymentOrderOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff orders view: payment orders that hold a captured payment nobody recorded, and recent
 * orders that were not paid. Read-only; nothing here acts on an order.
 *
 * <p>Keyed by payment order, which is why it is apart from {@link PaymentController}'s
 * agreement-keyed routes. Authorization lives in the filter chain: {@code SecurityConfig} pins the
 * exact GET to the STAFF role, so a refused caller reaches no read.
 *
 * <p>Not transactional, deliberately: {@link PaymentOrderOverview#overview} must start its own
 * transaction for its isolation level to apply.
 */
@RestController
@RequestMapping("/api/staff/payment-orders")
public class StaffPaymentOrderController {

  private final PaymentOrderOverview overview;

  public StaffPaymentOrderController(PaymentOrderOverview overview) {
    this.overview = overview;
  }

  @GetMapping
  public PaymentOrdersResponse list() {
    return overview.overview();
  }
}

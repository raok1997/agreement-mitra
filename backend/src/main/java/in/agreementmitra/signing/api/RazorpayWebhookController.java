package in.agreementmitra.signing.api;

import in.agreementmitra.signing.payment.RazorpayWebhookService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives payment callbacks from Razorpay. MUST be publicly reachable in local dev (use a
 * cloudflared/ngrok tunnel) or the callback never arrives - exactly as the eSign webhook already
 * requires.
 *
 * <p><b>The body is taken as a {@code String}, and that is load-bearing.</b> Verification computes
 * HMAC-SHA256 over the <b>raw bytes</b> keyed by the webhook secret, so the body must reach the
 * digest byte-for-byte as received. Binding it to a DTO and letting Jackson re-serialise it would
 * change key order, whitespace, and number formatting, and the digest would never match. The
 * existing eSign controller takes a {@code String} for the same reason; this follows that precedent
 * rather than inventing a second one.
 *
 * <p><b>Two secrets, and this endpoint uses only one of them.</b> Webhooks are verified with the
 * <b>webhook secret</b>. The API <b>key secret</b> signs the value the checkout handler returns to
 * the browser and has no business here; conflating the two is the classic integration error and is
 * made structurally impossible by keeping them separate properties.
 *
 * <p>Responses are deliberately coarse, mirroring the eSign webhook: {@code 401} when verification
 * fails (no side effect of any kind), {@code 202} once verified - the same acknowledgement whether
 * or not the order is one we hold, so the endpoint is not an existence oracle. Neither response
 * echoes the body, the signature, or any credential.
 */
@RestController
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {

  /** The header Razorpay presents its body signature in. */
  private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";

  private final RazorpayWebhookService webhookService;

  public RazorpayWebhookController(RazorpayWebhookService webhookService) {
    this.webhookService = webhookService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> onPaymentEvent(
      @RequestBody String payload,
      @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
    if (!webhookService.handle(payload, signature)) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.accepted().build();
  }
}

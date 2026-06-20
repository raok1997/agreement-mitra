package in.agreementmitra.signing.api;

import in.agreementmitra.signing.EsignProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives signing-completion callbacks from the aggregator. MUST be publicly reachable in local
 * dev (use a cloudflared/ngrok tunnel). Verify the signature before doing anything with the payload
 * — never log it verbatim.
 */
@RestController
@RequestMapping("/api/webhooks/esign")
public class WebhookController {

  private final EsignProvider esignProvider;

  public WebhookController(EsignProvider esignProvider) {
    this.esignProvider = esignProvider;
  }

  @PostMapping
  public ResponseEntity<Void> onSigningEvent(
      @RequestBody String payload,
      @RequestHeader(name = "X-Signature", required = false) String signature) {
    if (!esignProvider.verifyWebhook(payload, signature)) {
      return ResponseEntity.status(401).build();
    }
    // TODO: parse event, advance the signing-request FSM, persist signed PDF to
    // object storage, push status to the SPA. Spec via OpenSpec.
    return ResponseEntity.accepted().build();
  }
}

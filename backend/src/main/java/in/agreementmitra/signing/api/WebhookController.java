package in.agreementmitra.signing.api;

import in.agreementmitra.signing.signingrequest.SigningRequestService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives signing-completion callbacks from the aggregator. MUST be publicly reachable in local
 * dev (use a cloudflared/ngrok tunnel). A single endpoint for both the success and error channels:
 * the service verifies the body MAC (the webhook's only authorization — the aggregator cannot
 * bearer-auth), then treats the webhook as a trigger and re-reads authoritative state. Never log
 * the payload verbatim.
 *
 * <p>Responses are deliberately coarse: {@code 401} when the MAC fails (no side effect), {@code
 * 202} once verified — the same ack whether or not the document is known, so the endpoint is not a
 * document-id existence oracle.
 */
@RestController
@RequestMapping("/api/webhooks/esign")
public class WebhookController {

  private final SigningRequestService signingRequestService;

  public WebhookController(SigningRequestService signingRequestService) {
    this.signingRequestService = signingRequestService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> onSigningEvent(@RequestBody String payload) {
    if (!signingRequestService.handleWebhook(payload)) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.accepted().build();
  }
}

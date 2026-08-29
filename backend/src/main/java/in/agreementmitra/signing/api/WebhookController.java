package in.agreementmitra.signing.api;

import in.agreementmitra.signing.WebhookHeaders;
import in.agreementmitra.signing.signingrequest.SigningRequestService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives signing-completion callbacks from the aggregator. MUST be publicly reachable in local
 * dev (use a cloudflared/ngrok tunnel). A single endpoint for both the success and error channels:
 * the service verifies the call through the active provider adapter, then treats the webhook as a
 * trigger and re-reads authoritative state. It branches on no untrusted body field, and never logs
 * the payload verbatim.
 *
 * <p><b>Why the headers are passed through.</b> Webhook authentication is provider-specific.
 * Leegality carries a MAC in the JSON body; ZOOP eSign v5 carries a per-transaction shared key in
 * the {@code webhook-security-key} HTTP <b>header</b>. Handing the adapter the request headers
 * keeps both mechanisms behind the one seam, and keeps this controller from parsing a vendor
 * payload itself - it never sees a document id, only the verified/rejected verdict.
 *
 * <p>Responses are deliberately coarse: {@code 401} when verification fails (no side effect),
 * {@code 202} once verified — the same ack whether or not the document is known, so the endpoint is
 * not a document-id existence oracle. Neither response echoes the body or the credential.
 */
@RestController
@RequestMapping("/api/webhooks/esign")
public class WebhookController {

  private final SigningRequestService signingRequestService;

  public WebhookController(SigningRequestService signingRequestService) {
    this.signingRequestService = signingRequestService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> onSigningEvent(
      @RequestBody String payload, @RequestHeader Map<String, String> headers) {
    if (!signingRequestService.handleWebhook(payload, WebhookHeaders.of(headers))) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.accepted().build();
  }
}

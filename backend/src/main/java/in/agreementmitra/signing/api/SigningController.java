package in.agreementmitra.signing.api;

import in.agreementmitra.signing.signingrequest.SigningRequestService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts an eSign request for an agreement and returns the per-signer signing URLs. The request
 * thread returns immediately — completion arrives later via {@link WebhookController}.
 *
 * <p>A non-UUID {@code agreementId} yields a 400 ProblemDetail (type mismatch, handled centrally);
 * an unknown agreement yields a 404 ProblemDetail (via {@code ResourceNotFoundException}). This
 * endpoint is unauthenticated today (no auth mechanism exists yet) — ownership authorization and
 * rate-limiting are deferred to a follow-up change.
 */
@RestController
@RequestMapping("/api/signing")
public class SigningController {

  private final SigningRequestService signingRequestService;

  public SigningController(SigningRequestService signingRequestService) {
    this.signingRequestService = signingRequestService;
  }

  @PostMapping("/{agreementId}/request")
  public ResponseEntity<SigningRequestResponse> requestSignature(@PathVariable UUID agreementId) {
    SigningRequestResponse response = signingRequestService.create(agreementId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}

package in.agreementmitra.signing.api;

import in.agreementmitra.ClientSourceResolver;
import in.agreementmitra.signing.recovery.RecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask for an agreement's recovery link to be re-sent, from its tracking reference.
 *
 * <p><b>This endpoint answers 202 with an empty body, always.</b> Unknown reference, unpaid
 * agreement, already claimed, nobody contactable, throttled, sent - one response for all of them.
 * That uniformity is the security control, not a simplification: the tracking reference carries far
 * less entropy than the agreement identifier, so an endpoint that revealed whether a reference
 * existed would make every paid agreement enumerable and leak full signer PII. Because the response
 * never varies, guessing references gains an attacker nothing, and any mail produced goes to the
 * legitimate parties rather than to whoever asked.
 *
 * <p>A future change that adds a "not found" branch, a different status for throttling, or a body
 * describing what happened has broken this. The distinctions live in the audit trail instead.
 *
 * <p>Anonymous by necessity: the customer this serves has no account and no identifier - the
 * reference is all they have.
 */
@RestController
@RequestMapping("/api/agreements/recovery")
public class RecoveryController {

  private final RecoveryService recoveryService;
  private final ClientSourceResolver sources;

  public RecoveryController(RecoveryService recoveryService, ClientSourceResolver sources) {
    this.recoveryService = recoveryService;
    this.sources = sources;
  }

  @PostMapping
  public ResponseEntity<Void> requestRecovery(
      @Valid @RequestBody RecoveryRequest request, HttpServletRequest httpRequest) {
    // The client address as resolved from the trusted proxy chain - never the proxy's own address
    // (which would put every customer in one bucket) and never a header the client controls.
    recoveryService.requestRecovery(request.reference(), sources.resolve(httpRequest));
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }
}

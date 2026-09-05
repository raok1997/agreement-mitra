package in.agreementmitra.signing.api;

import in.agreementmitra.signing.signingrequest.SigningRequestService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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

  /**
   * Per-party signing progress: each party's own status alongside the aggregate state, so a
   * customer can see that (say) the owner has signed and the tenant has not.
   *
   * <p>Authorization is applied in the service because it depends on the row's owner, which the
   * filter chain cannot see: a STAFF caller may read any agreement; anyone else sees an unowned
   * agreement or one they own, and gets the same 404 as an unknown id otherwise. The response
   * carries no eKYC-derived signer data, no signing URL, and no provider credential.
   */
  @GetMapping("/{agreementId}/progress")
  public SigningProgressResponse progress(
      @PathVariable UUID agreementId, Authentication authentication) {
    UUID identityId =
        authentication != null && authentication.getPrincipal() instanceof UUID id ? id : null;
    return signingRequestService.progress(agreementId, identityId, isStaff(authentication));
  }

  /**
   * Whether the caller holds the STAFF authority the session filter reads from the identity row.
   */
  private static boolean isStaff(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(a -> ROLE_STAFF.equals(a.getAuthority()));
  }

  private static final String ROLE_STAFF = "ROLE_STAFF";
}

package in.agreementmitra.signing.api;

import in.agreementmitra.signing.signingrequest.SigningRequestService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff remedies for a signing request that is in flight but stuck: extend its window, or ask the
 * provider to re-send its invitations.
 *
 * <p>Both act on the <b>same</b> provider transaction. That is the requirement, not an
 * implementation detail: creating a fresh transaction would be a second document and a second
 * vendor charge for the same agreement, and would orphan the signatures already collected.
 *
 * <p>Sequential signing is why these exist at all - the tenant cannot start until the owner
 * finishes, so a stalled owner blocks everything and someone has to be able to nudge it.
 *
 * <p><b>Authorization lives in the filter chain</b>, pinned to the STAFF role on these exact paths,
 * so a refused caller never reaches an agreement lookup. A provider that supports neither operation
 * surfaces as an {@code UnsupportedOperationException} rather than silently doing nothing.
 */
@RestController
@RequestMapping("/api/staff/signing")
public class StaffSigningController {

  private final SigningRequestService signingRequestService;

  public StaffSigningController(SigningRequestService signingRequestService) {
    this.signingRequestService = signingRequestService;
  }

  /**
   * Extend the signing window by {@code minutes} (default one week, matching the initial window).
   * 404 when the agreement has no in-flight signing request.
   */
  @PostMapping("/{agreementId}/extend")
  public ResponseEntity<Void> extend(
      @PathVariable UUID agreementId,
      @RequestParam(name = "minutes", defaultValue = "10080") int minutes) {
    signingRequestService.extendSigningWindow(agreementId, minutes);
    return ResponseEntity.accepted().build();
  }

  /** Re-send the provider-delivered invitations for the same transaction. */
  @PostMapping("/{agreementId}/resend")
  public ResponseEntity<Void> resend(@PathVariable UUID agreementId) {
    signingRequestService.resendInvitations(agreementId);
    return ResponseEntity.accepted().build();
  }
}

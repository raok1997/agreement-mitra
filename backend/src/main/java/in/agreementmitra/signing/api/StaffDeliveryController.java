package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.delivery.SignedDocumentDeliveryService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff view of delivery, and the one manual lever over it.
 *
 * <p>It exists because delivery can fail in ways nothing automatic can fix: a party with no
 * signing-verified address, or a mailbox that rejected the message outright. Retrying those forever
 * would only guarantee that nobody ever looks at them, so they stop and land here, where a human
 * can see which party is affected and why.
 *
 * <p><b>Authorization lives in the filter chain</b>, pinned to the STAFF role on these exact paths,
 * so a refused caller never reaches a delivery or agreement lookup and the endpoint cannot be used
 * as an existence oracle.
 *
 * <p>The diagnostic view carries a <b>redacted</b> recipient address and never the document itself:
 * enough to act on a failure, not enough to read somebody's agreement off a support screen.
 */
@RestController
@RequestMapping("/api/staff/deliveries")
public class StaffDeliveryController {

  private final SignedDocumentDeliveryService deliveryService;
  private final AgreementService agreementService;

  public StaffDeliveryController(
      SignedDocumentDeliveryService deliveryService, AgreementService agreementService) {
    this.deliveryService = deliveryService;
    this.agreementService = agreementService;
  }

  /** Every recipient's delivery record for one agreement. Empty list if nothing was ever queued. */
  @GetMapping("/{agreementId}")
  public List<DeliveryStatusResponse> forAgreement(@PathVariable UUID agreementId) {
    return deliveryService.statusFor(agreementId);
  }

  /** The agreement's terminal fulfilment state, so a closed agreement reads as closed and why. */
  @GetMapping("/{agreementId}/closure")
  public ClosureStateResponse closure(@PathVariable UUID agreementId) {
    return agreementService.closureView(agreementId);
  }

  /**
   * Deliberately re-send one recipient's copy. Recorded as a <b>separate, attributed attempt</b>
   * (who asked, when, and a re-send counter distinct from the automatic attempt counter) - "we
   * tried again by ourselves" and "a staff member asked us to" are different facts about a legal
   * document.
   *
   * <p>Allowed on a closed agreement: closure means "no work outstanding", not "no longer
   * available", and re-sending a document already delivered advances nothing.
   */
  @PostMapping("/{deliveryId}/resend")
  public List<DeliveryStatusResponse> resend(
      @PathVariable UUID deliveryId, @AuthenticationPrincipal UUID staffIdentityId) {
    return deliveryService.resend(deliveryId, staffIdentityId);
  }
}

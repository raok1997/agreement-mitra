package in.agreementmitra.signing.api;

import java.util.List;
import java.util.UUID;

/**
 * Response view of a created signing request: the provider document id and one signing URL per
 * signer. The signing URL is a bearer capability for that signer — it is returned to the caller but
 * never logged.
 */
public record SigningRequestResponse(String documentId, List<InviteeView> invitees) {

  public record InviteeView(UUID signerId, String email, String signUrl, String expiry) {}
}

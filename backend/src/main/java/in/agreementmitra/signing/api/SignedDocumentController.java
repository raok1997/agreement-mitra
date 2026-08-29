package in.agreementmitra.signing.api;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.agreement.AgreementService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The party-facing copy of the signed agreement: the durable in-app copy that covers everything the
 * emailed attachment does not - an oversize document, a bounced mailbox, a party who deleted the
 * email, or someone digging the tenancy out years later.
 *
 * <p><b>Application-mediated, never a presigned URL</b> (design D8). The bytes are streamed from
 * the private bucket after an authorization check, so there is a single authorization point and the
 * bucket stays private. A presigned URL is a bearer capability that outlives the check that issued
 * it and can be forwarded; for a document carrying both parties' names, the property address, the
 * financial terms and a stamp certificate, an authenticated request each time is the simpler and
 * safer default.
 *
 * <p><b>Authorization is evaluated before the agreement is looked up</b>, so this is not an
 * existence oracle: a caller with no relationship to an agreement gets the same 404 whether or not
 * it exists. Ownership reuses the one rule the rest of the surface uses ({@code isAccessibleBy}) -
 * an unowned agreement is reachable by anyone presenting its unguessable id, a claimed one only by
 * its owner - and STAFF may read any agreement for support.
 *
 * <p><b>There is deliberately no party-facing path to the audit trail.</b> It carries eKYC-derived
 * detail and remains an internal evidentiary artifact, produced on request; the fail-closed
 * security baseline denies any such route by default rather than this controller refusing one.
 *
 * <p>Retrieval keeps working <b>indefinitely</b>, including after the agreement closes: closure
 * means "no work outstanding", not "no longer available".
 */
@RestController
@RequestMapping("/api/agreements")
public class SignedDocumentController {

  private static final String ROLE_STAFF = "ROLE_STAFF";

  private final AgreementService agreementService;
  private final SigningRequestQuery signingRequestQuery;
  private final BlobStore blobStore;

  public SignedDocumentController(
      AgreementService agreementService,
      SigningRequestQuery signingRequestQuery,
      BlobStore blobStore) {
    this.agreementService = agreementService;
    this.signingRequestQuery = signingRequestQuery;
    this.blobStore = blobStore;
  }

  /**
   * Stream the signed agreement to a party who owns it (or to STAFF). 404 - identical to the
   * unknown-agreement 404 - when the caller has no relationship to it, and also when signing has
   * not completed or the artifact is not stored yet, so the endpoint reveals nothing about progress
   * either.
   *
   * <p>Marked {@code no-store}: the PDF carries party PII and must not sit in a shared cache. The
   * bytes are never logged.
   */
  @GetMapping("/{id}/signed-document")
  public ResponseEntity<byte[]> signedDocument(
      @PathVariable UUID id, Authentication authentication) {
    UUID identityId =
        authentication != null && authentication.getPrincipal() instanceof UUID principal
            ? principal
            : null;
    // AUTHORIZATION FIRST. A refused caller never reaches the artifact lookup, so the refusal
    // cannot differ between an agreement that exists and one that does not.
    if (!isStaff(authentication) && !agreementService.isAccessibleBy(id, identityId)) {
      throw new ResourceNotFoundException("Agreement not found: " + id);
    }
    String key =
        signingRequestQuery
            .signedPdfKeyForAgreement(id)
            .orElseThrow(() -> new ResourceNotFoundException("No signed document: " + id));
    byte[] pdf = blobStore.get(key);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("signed-rental-agreement.pdf")
                .build()
                .toString())
        .cacheControl(CacheControl.noStore())
        .body(pdf);
  }

  /**
   * Whether the caller holds the STAFF authority the session filter reads from the identity row.
   */
  private static boolean isStaff(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(a -> ROLE_STAFF.equals(a.getAuthority()));
  }
}

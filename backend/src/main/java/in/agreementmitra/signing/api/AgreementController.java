package in.agreementmitra.signing.api;

import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.signing.agreement.AgreementDocumentService;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.DraftService;
import in.agreementmitra.signing.contact.DraftDeliveryService;
import in.agreementmitra.signing.signingrequest.SigningRequestService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Create and read rental agreements, and accept the uploaded draft PDF. Part of the signing
 * module's public {@code api} surface, alongside {@link SigningController}. The request thread
 * returns synchronously — this controller does not start any signing (that is the signing CR).
 */
@RestController
@RequestMapping("/api/agreements")
public class AgreementController {

  private final AgreementService agreementService;
  private final DraftService draftService;
  private final AgreementDocumentService agreementDocumentService;
  private final SigningRequestService signingRequestService;
  private final DraftDeliveryService draftDeliveryService;

  public AgreementController(
      AgreementService agreementService,
      DraftService draftService,
      AgreementDocumentService agreementDocumentService,
      SigningRequestService signingRequestService,
      DraftDeliveryService draftDeliveryService) {
    this.agreementService = agreementService;
    this.draftService = draftService;
    this.agreementDocumentService = agreementDocumentService;
    this.signingRequestService = signingRequestService;
    this.draftDeliveryService = draftDeliveryService;
  }

  /**
   * <b>Finalise:</b> place the order and end the customer's involvement until sign-off. Freezes the
   * agreement's terms, creates the signing request in the durable {@code PDF_GENERATED} state, and
   * returns the tracking reference the customer keeps - the same value staff will quote when they
   * attach the purchased e-stamp, and the same value printed on the document.
   *
   * <p>Idempotent: finalising twice returns the same reference and places no second order. 404 for
   * an unknown agreement; 409 if there is no generated draft to finalise.
   *
   * <p>Unauthenticated today, exactly like the rest of the anonymous drafting surface (create /
   * draft / generate). TEMPORARY - tighten together with them when ownership authorization lands.
   * When the payment gate arrives, order placement moves behind payment confirmation.
   */
  @PostMapping("/{id}/finalise")
  public FinaliseResponse finalise(@PathVariable UUID id) {
    return signingRequestService.finalise(id);
  }

  @PostMapping
  public ResponseEntity<AgreementResponse> create(
      @Valid @RequestBody CreateAgreementRequest request) {
    AgreementResponse created = agreementService.create(request);
    return ResponseEntity.created(URI.create("/api/agreements/" + created.id())).body(created);
  }

  /**
   * List the authenticated caller's agreements (the resume view), most-recent first, each with a
   * derived status + {@code editable} flag. The owner is the authenticated principal (an identity
   * id the session filter set) -- never a query/body field. Authenticated-only (see {@code
   * SecurityConfig}), so {@code identityId} is always present here.
   */
  @GetMapping
  public List<AgreementSummaryResponse> listMine(@AuthenticationPrincipal UUID identityId) {
    return agreementService.listOwnedBy(identityId);
  }

  /**
   * Read one agreement by id, owner-scoped (D5): an <b>unowned</b> draft is readable by any caller
   * presenting the id (the unguessable id is a bearer capability); once <b>claimed</b>, only its
   * owner may read it -- a non-owner or anonymous caller gets the same 404 as an unknown id, so
   * ownership cannot be probed. This route is {@code permitAll}, so {@code identityId} may be null
   * (anonymous); the owner check is here in the handler because it depends on the row's owner,
   * which the filter chain cannot see.
   */
  @GetMapping("/{id}")
  public AgreementResponse get(@PathVariable UUID id, @AuthenticationPrincipal UUID identityId) {
    return agreementService
        .findByIdForReader(id, identityId)
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
  }

  /**
   * Claim (save) an unowned agreement into the authenticated caller's identity. Idempotent for the
   * same owner; an unknown id or one owned by a different identity returns 404 (no ownership
   * oracle). Owner is the authenticated principal, never a body field (anti-mass-assignment).
   * Returns the (now owner-scoped) agreement.
   */
  @PostMapping("/{id}/claim")
  public AgreementResponse claim(@PathVariable UUID id, @AuthenticationPrincipal UUID identityId) {
    agreementService.claim(id, identityId);
    return agreementService
        .findByIdForReader(id, identityId)
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
  }

  /**
   * Edit an agreement the caller owns: fully replace its mutable terms + party list (same body as
   * create). Owner-scoped (404 for an unknown/other-owner id) and allowed only while no signing
   * request exists (409 once frozen). Owner is the authenticated principal; a body {@code
   * ownerIdentityId}/{@code id} would be ignored (the record carries no such field). Returns the
   * updated agreement.
   */
  /**
   * Set party contacts before payment, for an agreement nobody owns yet (design D16).
   *
   * <p>Anonymous like the rest of the pre-payment surface: the unguessable id is the capability.
   * Scoped as tightly as the route can be - contacts only, and refused once a signing request
   * exists - so that opening it costs strictly less than opening the full edit path, which stays
   * authenticated.
   *
   * <p>The principal is read but never required. It is what lets the agreement's OWN owner use the
   * same screen: signing in must not cost a customer the ability to save their contacts. An
   * agreement owned by anybody else returns the same 404 as an unknown one, so ownership cannot be
   * probed here either.
   */
  @PatchMapping("/{id}/contacts")
  public AgreementResponse updateContacts(
      @PathVariable UUID id,
      @AuthenticationPrincipal UUID identityId,
      @Valid @RequestBody ContactsUpdateRequest request) {
    AgreementResponse updated = agreementService.updateContacts(id, identityId, request);
    // Send the draft to every party now that we know how to reach them (design D17). Outside the
    // save transaction, and non-fatal by construction: a mail failure must not cost the customer
    // the contacts they just entered, nor stand between them and paying.
    draftDeliveryService.sendStoredDraftToParties(updated);
    return updated;
  }

  @PutMapping("/{id}")
  public AgreementResponse update(
      @PathVariable UUID id,
      @AuthenticationPrincipal UUID identityId,
      @Valid @RequestBody CreateAgreementRequest request) {
    return agreementService.update(id, identityId, request);
  }

  /**
   * Render the agreement's rental-agreement document and return it as an <b>inline</b> PDF for
   * on-screen preview. Rendered on demand and <b>not stored</b> (that is the generate step). {@code
   * id} is bound as a {@link UUID} (a non-UUID path is a 400 before any work); an unknown id is a
   * 404. The response is marked {@code no-store} (the PDF carries party PII) and the bytes are
   * never logged. Returns the PDF bytes only.
   */
  @GetMapping("/{id}/preview")
  public ResponseEntity<byte[]> preview(@PathVariable UUID id) {
    byte[] pdf = agreementDocumentService.renderPreview(id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename("rental-agreement.pdf").build().toString())
        .cacheControl(CacheControl.noStore())
        .body(pdf);
  }

  /**
   * Render the agreement's rental-agreement document and <b>store it as the signable draft</b>
   * through the existing draft path, then <b>pin</b> the effective template's identity so the
   * stored draft is reproducible. Orchestrates three proxied, independently-transactional
   * collaborators in a fixed order -- render ({@code renderForDraft}, full validation -> parity PDF
   * + identity), store ({@code attachDraft}), then pin ({@code pinEffectiveTemplate}) -- so a
   * rejected/frozen store pins nothing. Overwrites any prior draft while no signing request exists;
   * {@code attachDraft} rejects with 409 once one does (draft locked, no re-pin). 404 for an
   * unknown agreement; a non-UUID path is a 400. Returns the agreement id only.
   */
  @PostMapping("/{id}/document")
  public ResponseEntity<Map<String, UUID>> generateDocument(@PathVariable UUID id) {
    DocumentProjectionResult result =
        agreementDocumentService.renderForDraft(id); // 404 if the agreement is unknown
    draftService.attachDraft(id, result.pdf()); // validate -> freeze-check (409) -> store -> attach
    agreementDocumentService.pinEffectiveTemplate(
        id, result.identity()); // pin after a stored draft
    return ResponseEntity.ok(Map.of("agreementId", id));
  }

  /**
   * Upload the agreement's draft PDF as multipart form-data (exactly one file part). {@code id} is
   * bound as a {@link UUID} so a non-UUID path is a 400 (type mismatch) before any storage key is
   * built — no path traversal. The attacker-controlled filename and declared content type are never
   * used. Returns the agreement id only — never the stored bytes.
   */
  @PostMapping("/{id}/draft")
  public ResponseEntity<Map<String, UUID>> uploadDraft(
      @PathVariable UUID id, MultipartHttpServletRequest request) {
    Map<String, MultipartFile> files = request.getFileMap();
    if (files.size() != 1) {
      throw new InvalidUploadException("expected exactly one file part, got " + files.size());
    }
    MultipartFile file = files.values().iterator().next();
    draftService.attachDraft(id, readBytes(file));
    return ResponseEntity.ok(Map.of("agreementId", id));
  }

  private static byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new InvalidUploadException("could not read the uploaded file");
    }
  }
}

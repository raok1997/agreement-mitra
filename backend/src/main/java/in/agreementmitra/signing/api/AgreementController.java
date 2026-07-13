package in.agreementmitra.signing.api;

import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.signing.agreement.AgreementDocumentService;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.DraftService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

  public AgreementController(
      AgreementService agreementService,
      DraftService draftService,
      AgreementDocumentService agreementDocumentService) {
    this.agreementService = agreementService;
    this.draftService = draftService;
    this.agreementDocumentService = agreementDocumentService;
  }

  @PostMapping
  public ResponseEntity<AgreementResponse> create(
      @Valid @RequestBody CreateAgreementRequest request) {
    AgreementResponse created = agreementService.create(request);
    return ResponseEntity.created(URI.create("/api/agreements/" + created.id())).body(created);
  }

  @GetMapping("/{id}")
  public AgreementResponse get(@PathVariable UUID id) {
    return agreementService
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
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

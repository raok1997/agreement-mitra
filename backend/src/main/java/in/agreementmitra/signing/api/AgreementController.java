package in.agreementmitra.signing.api;

import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.DraftService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
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

  public AgreementController(AgreementService agreementService, DraftService draftService) {
    this.agreementService = agreementService;
    this.draftService = draftService;
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

package in.agreementmitra.signing.api;

import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.signing.signingrequest.StampIntakeCommand;
import in.agreementmitra.signing.signingrequest.StampIntakeService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Staff e-stamp intake: upload the scan of a certificate purchased on the SHCIL portal, plus its
 * metadata, and have it composited onto the named agreement's instrument.
 *
 * <p><b>Authorization lives in the filter chain, not here.</b> {@code SecurityConfig} pins this
 * exact path to the STAFF role, so an unauthenticated caller gets 401 and a non-staff caller gets
 * 403 <em>before this handler is ever entered</em>. That ordering is the point: no agreement lookup
 * happens on a refused request, so the endpoint cannot be used as an existence oracle for other
 * customers' agreements, and the refusal is identical whether or not the reference exists.
 *
 * <p>The agreement is named by its persisted, checksummed staff reference - never by the
 * display-only tracking number, which is derived at render time and is not collision-free. An
 * unresolvable reference is a 404; an already-stamped agreement or an already-spent certificate is
 * a 409; a bad image or missing metadata is a 400. Nothing in any error body echoes a submitted
 * value.
 */
@RestController
@RequestMapping("/api/staff/estamp")
public class StampIntakeController {

  /** The multipart part carrying the scan. Exactly one file part is accepted. */
  private static final String SCAN_PART = "scan";

  private final StampIntakeService stampIntakeService;

  public StampIntakeController(StampIntakeService stampIntakeService) {
    this.stampIntakeService = stampIntakeService;
  }

  /**
   * The fulfilment queue: every order awaiting an e-stamp, longest-waiting first. Backs the staff
   * console, so an operator never has to read a tracking reference out of the database - and,
   * because each entry carries its own reference, never has to re-type one either.
   */
  @GetMapping("/queue")
  public List<StampQueueEntry> queue() {
    return stampIntakeService.awaitingStampQueue();
  }

  /**
   * Accept the scan + certificate metadata. {@code @Valid} on the metadata runs before the body, so
   * a missing mandatory field is a 400 with a field-level error list and no side effect at all.
   */
  @PostMapping
  public StampIntakeResponse upload(
      @AuthenticationPrincipal UUID staffIdentityId,
      @Valid @ModelAttribute StampIntakeRequest request,
      MultipartHttpServletRequest multipart) {
    byte[] scan = readScan(multipart);
    StampIntakeCommand command =
        new StampIntakeCommand(
            request.agreementReference(),
            scan,
            request.certificateNumber(),
            request.issueDate(),
            request.dutyAmount(),
            request.jurisdiction(),
            request.descriptionOfDocument(),
            request.purchasedBy(),
            request.signingRequested());
    return stampIntakeService.attach(staffIdentityId, command);
  }

  /**
   * Exactly one file part, read as bytes. The attacker-controlled filename and declared content
   * type are never used - the scan's own magic bytes decide what it is, downstream in the
   * validator.
   */
  private static byte[] readScan(MultipartHttpServletRequest multipart) {
    Map<String, MultipartFile> files = multipart.getFileMap();
    if (files.size() != 1) {
      throw new InvalidUploadException("expected exactly one file part, got " + files.size());
    }
    MultipartFile file = files.get(SCAN_PART);
    if (file == null) {
      throw new InvalidUploadException("the file part must be named '" + SCAN_PART + "'");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new InvalidUploadException("could not read the uploaded certificate scan");
    }
  }
}

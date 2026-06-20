package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.AgreementService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create and read rental agreements. Part of the signing module's public {@code api} surface,
 * alongside {@link SigningController}. The request thread returns the persisted agreement
 * synchronously — this CR does not start any signing (that is a later CR).
 */
@RestController
@RequestMapping("/api/agreements")
public class AgreementController {

  private final AgreementService agreementService;

  public AgreementController(AgreementService agreementService) {
    this.agreementService = agreementService;
  }

  @PostMapping
  public ResponseEntity<AgreementResponse> create(
      @Valid @RequestBody CreateAgreementRequest request) {
    AgreementResponse created = agreementService.create(request);
    return ResponseEntity.created(URI.create("/api/agreements/" + created.id())).body(created);
  }

  @GetMapping("/{id}")
  public ResponseEntity<AgreementResponse> get(@PathVariable UUID id) {
    return agreementService
        .findById(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

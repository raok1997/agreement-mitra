package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Agreement;
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
 * Create + fetch HTTP surface for the rental Agreement aggregate. Synchronous CRUD only — no
 * signing flow here (the Agreement is status-less). Reachable in the default-deny baseline via the
 * {@code permitAll} rule for {@code /api/agreements/**}.
 */
@RestController
@RequestMapping("/api/agreements")
public class AgreementController {

  private final AgreementService service;

  public AgreementController(AgreementService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<AgreementResponse> create(
      @Valid @RequestBody CreateAgreementRequest request) {
    Agreement saved =
        service.create(
            request.landlordName(),
            request.tenantName(),
            request.propertyAddress(),
            request.monthlyRent(),
            request.securityDeposit(),
            request.termMonths());
    return ResponseEntity.created(URI.create("/api/agreements/" + saved.getId()))
        .body(AgreementResponse.from(saved));
  }

  @GetMapping("/{id}")
  public ResponseEntity<AgreementResponse> get(@PathVariable UUID id) {
    return service
        .find(id)
        .map(AgreementResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

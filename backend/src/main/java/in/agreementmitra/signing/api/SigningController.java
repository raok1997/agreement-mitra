package in.agreementmitra.signing.api;

import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.EsignProvider;
import org.springframework.web.bind.annotation.*;

/**
 * Triggers a signing request and returns the signing URL. The request thread
 * returns immediately — completion arrives later via {@link WebhookController}.
 */
@RestController
@RequestMapping("/api/signing")
public class SigningController {

  private final EsignProvider esignProvider;

  public SigningController(EsignProvider esignProvider) {
    this.esignProvider = esignProvider;
  }

  // TODO: accept an agreementId, load + render the PDF via the documents module,
  // persist a signing request row in DRAFT->PDF_GENERATED->SIGN_REQUESTED, then:
  @PostMapping("/{agreementId}/request")
  public SignSession requestSignature(@PathVariable String agreementId) {
    throw new UnsupportedOperationException(
        "Spec this with OpenSpec: /opsx:propose create-signing-request");
  }
}

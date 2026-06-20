package in.agreementmitra.signing.leegality;

import in.agreementmitra.signing.*;
import org.springframework.stereotype.Component;

/**
 * Leegality (sandbox) adapter. Internal to the signing module — nothing outside the module
 * references this class directly; they depend on {@link EsignProvider}.
 *
 * <p>TODO: wire the Leegality sandbox REST API (create request, status, download) and HMAC webhook
 * verification. Read keys from env, never hardcode.
 */
@Component
public class LeegalityEsignProvider implements EsignProvider {

  @Override
  public SignSession createSignRequest(SignRequest request) {
    throw new UnsupportedOperationException("TODO: call Leegality sandbox create-request");
  }

  @Override
  public SignatureStatus getStatus(String providerRequestId) {
    throw new UnsupportedOperationException("TODO: call Leegality status endpoint");
  }

  @Override
  public SignedDocument download(String providerRequestId) {
    throw new UnsupportedOperationException("TODO: call Leegality download endpoint");
  }

  @Override
  public boolean verifyWebhook(String payload, String signatureHeader) {
    throw new UnsupportedOperationException("TODO: verify Leegality webhook HMAC");
  }
}

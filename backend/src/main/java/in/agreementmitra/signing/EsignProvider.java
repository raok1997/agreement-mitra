package in.agreementmitra.signing;

/**
 * Abstraction over an Aadhaar eSign aggregator. Implementations live in provider-specific internal
 * packages (e.g. {@code .leegality}). Swapping vendors (Leegality - Digio) must be a new adapter,
 * nothing more.
 */
public interface EsignProvider {

  /** Create a signing request with the vendor; returns the signing URL. */
  SignSession createSignRequest(SignRequest request);

  /** Fetch current status from the vendor (reconciliation fallback). */
  SignatureStatus getStatus(String providerRequestId);

  /** Download the completed signed document + audit trail. */
  SignedDocument download(String providerRequestId);

  /**
   * Verify an inbound webhook is authentic before acting on it. Treat every webhook as untrusted
   * until this returns true.
   */
  boolean verifyWebhook(String payload, String signatureHeader);
}

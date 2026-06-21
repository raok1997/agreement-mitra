package in.agreementmitra.signing;

import java.util.Optional;

/**
 * Abstraction over an Aadhaar eSign aggregator. Implementations live in provider-specific internal
 * packages (e.g. {@code .leegality}). Swapping vendors (Leegality - Digio) must be a new adapter,
 * nothing more — all vendor specifics (URLs, auth, payload shapes, MAC algorithm) stay behind here.
 */
public interface EsignProvider {

  /**
   * Create a signing request with the vendor; returns the document id + per-invitee signing URLs.
   */
  SignSession createSignRequest(SignRequest request);

  /**
   * Read the authoritative status from the vendor for a document. Used both by the webhook path (as
   * the source of truth, since the webhook itself is only a trigger) and by the reconciliation
   * fallback for missed hooks. A still-in-flight document maps to the non-terminal {@link
   * SignatureStatus#SIGN_REQUESTED}.
   */
  SignatureStatus getStatus(String providerDocumentId);

  /** Download the completed signed document + audit trail. */
  SignedDocument download(String providerDocumentId);

  /**
   * Verify an inbound webhook is authentic and, if so, return the document id it concerns. The MAC
   * lives in the JSON body (not an HTTP header), so the adapter parses both the MAC and the
   * document id from the payload. The MAC covers only the document id — the rest of the body is
   * untrusted, so callers MUST re-read authoritative state via {@link #getStatus(String)} rather
   * than trusting any status field in the payload. Returns empty when verification fails (treat
   * every webhook as untrusted until this returns a value).
   */
  Optional<String> verifyWebhook(String payload);
}

package in.agreementmitra.signing;

import java.util.Optional;

/**
 * Abstraction over an Aadhaar eSign aggregator. Implementations live in provider-specific internal
 * packages (e.g. {@code .leegality}, {@code .zoop}); exactly one is active, selected by the {@code
 * esign.provider} configuration key. Swapping vendors must be a new adapter, nothing more - all
 * vendor specifics (URLs, auth, payload shapes, the webhook authentication mechanism, and
 * signature-placement translation) stay behind here.
 */
public interface EsignProvider {

  /**
   * Create a signing request with the vendor; returns the document id + per-invitee signing URLs,
   * plus (where the vendor issues one) the per-transaction webhook key the module must persist.
   */
  SignSession createSignRequest(SignRequest request);

  /**
   * Read the authoritative per-invitee status from the vendor for a document. Used both by the
   * webhook path (as the source of truth, since the webhook itself is only a trigger) and by the
   * reconciliation fallback for missed hooks. Returns one entry per invitee; the aggregate FSM
   * decision is computed by aggregating these (a still-in-flight invitee maps to {@link
   * InviteeStatus#PENDING}).
   */
  DocumentStatusView getStatus(String providerDocumentId);

  /**
   * Download the completed signed document + audit trail (each with its provider-declared content
   * type). If the provider exposes the artifacts via a URL, the adapter MUST pin that URL's host
   * against the configured provider-host <b>allowlist</b> before fetching (no arbitrary outbound
   * fetch). An allowlist rather than a single host: some providers hand back expiring links on a
   * different host from their API.
   */
  SignedDocument download(String providerDocumentId);

  /**
   * Parse the transaction/document id an <b>unverified</b> webhook body claims to concern - step
   * one of parse-then-verify (design D1).
   *
   * <p>The returned value is <b>untrusted</b>. Its only legitimate use is to look up the secret
   * this transaction was issued, so that {@link #verifyWebhook} can check the presented credential
   * against it. It MUST NOT drive any state change, and callers MUST NOT treat a parse as
   * authentication. Empty when the body is unreadable or names no transaction.
   */
  Optional<String> parseWebhookTransactionId(String payload);

  /**
   * Verify an inbound webhook is authentic and, if so, return the document id it concerns - step
   * two of parse-then-verify. Returns empty when verification fails (treat every webhook as
   * untrusted until this returns a value).
   *
   * <p>Two mechanisms are supported behind this one method, because vendors differ:
   *
   * <ul>
   *   <li>a <b>body MAC</b> over the document id under a config-wide secret (Leegality) - the
   *       adapter reads both from {@code payload} and ignores {@code headers} / {@code
   *       storedWebhookKey};
   *   <li>a <b>per-transaction shared key in a transport header</b> (ZOOP, {@code
   *       webhook-security-key}) - the adapter compares the header value against {@code
   *       storedWebhookKey} in constant time.
   * </ul>
   *
   * <p>Whichever mechanism verified the call, the body remains entirely untrusted: a header key
   * proves only that the caller holds the key and binds nothing to the payload, so callers MUST
   * re-read authoritative state via {@link #getStatus(String)} rather than trusting any status
   * field in the payload (design D2).
   *
   * @param payload the raw request body - untrusted
   * @param headers the request's transport headers, where a header-borne credential lives
   * @param storedWebhookKey the secret stored for the transaction {@link
   *     #parseWebhookTransactionId} named, decrypted by the module; {@code null} when the vendor
   *     issues no per-transaction key or the transaction is unknown. The adapter never reaches for
   *     persistence itself - that would put storage inside the vendor boundary.
   */
  Optional<String> verifyWebhook(String payload, WebhookHeaders headers, String storedWebhookKey);

  /**
   * Extend a still-pending transaction's signing window, <b>without</b> creating a new transaction
   * (and therefore without incurring a second charge). A stalled first signer is the normal reason:
   * sequential signing means the second party cannot start until the first finishes.
   *
   * <p>Optional - the default refuses, for a provider that offers no such operation. Callers must
   * treat that refusal as "not available with this vendor", not as a failure of the transaction.
   *
   * @param additionalMinutes how much longer the window should run
   */
  default void extendExpiry(String providerDocumentId, int additionalMinutes) {
    throw new UnsupportedOperationException("This provider cannot extend a pending transaction");
  }

  /**
   * Re-send the provider-delivered invitations for a still-pending transaction, without creating a
   * new transaction or incurring a second charge - the fallback when an invitation email does not
   * arrive. Optional, like {@link #extendExpiry}.
   */
  default void resendInvitations(String providerDocumentId) {
    throw new UnsupportedOperationException("This provider cannot re-send invitations");
  }
}

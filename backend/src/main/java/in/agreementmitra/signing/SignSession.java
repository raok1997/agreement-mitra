package in.agreementmitra.signing;

/**
 * Handle returned after creating a signing request with a provider.
 *
 * @param providerRequestId the vendor's id for this request (store it)
 * @param signingUrl URL to redirect/show the signer
 */
public record SignSession(String providerRequestId, String signingUrl) {}

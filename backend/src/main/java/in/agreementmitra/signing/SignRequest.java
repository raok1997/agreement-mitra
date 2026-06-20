package in.agreementmitra.signing;

/**
 * Vendor-neutral request to start an eSign. No Aadhaar/OTP data lives here — the signer
 * authenticates on the ESP page, not through our API.
 *
 * @param agreementId our internal agreement id
 * @param signerName display name of the signer
 * @param signerEmail where to send the signing invite
 * @param unsignedPdf bytes of the rendered, unsigned agreement
 */
public record SignRequest(
    String agreementId, String signerName, String signerEmail, byte[] unsignedPdf) {}

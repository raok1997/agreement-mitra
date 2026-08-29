package in.agreementmitra.signing.api;

import java.util.UUID;

/**
 * What the customer gets back when they finalise: their agreement's <b>tracking reference</b> and
 * the resulting order status.
 *
 * <p>Finalising is the end of the customer's involvement until they are invited to sign. The
 * reference is the one thing they need to keep - it is the same value staff quote when they attach
 * the purchased e-stamp, and the same value printed on the document, so a support conversation
 * about "my agreement" has exactly one number in it.
 */
public record FinaliseResponse(UUID agreementId, String trackingReference, String status) {}

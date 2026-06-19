package in.agreementmitra.signing;

/**
 * Result delivered (typically via webhook) once signing completes.
 *
 * @param providerRequestId vendor id this result corresponds to
 * @param signedPdf         signed PDF bytes (persist to object storage)
 * @param auditTrail        provider audit trail / evidence (store alongside)
 */
public record SignedDocument(String providerRequestId, byte[] signedPdf, byte[] auditTrail) {}

package in.agreementmitra.signing;

/**
 * Result delivered (typically via webhook completion) once signing finishes. Both artifacts carry
 * their provider-declared content type so the audit trail is stored opaquely with the right MIME.
 *
 * @param providerDocumentId vendor document id this result corresponds to
 * @param signedPdf signed PDF bytes (persist to object storage — never Postgres, never logged)
 * @param signedPdfContentType content type of the signed PDF (e.g. {@code application/pdf})
 * @param auditTrail provider audit trail / evidence — store opaquely alongside; never parsed/logged
 * @param auditTrailContentType content type of the audit trail (default {@code
 *     application/octet-stream} when the provider declares none)
 */
public record SignedDocument(
    String providerDocumentId,
    byte[] signedPdf,
    String signedPdfContentType,
    byte[] auditTrail,
    String auditTrailContentType) {}

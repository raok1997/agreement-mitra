package in.agreementmitra.documents.api;

/**
 * The result of a generate projection: the rendered {@code pdf} bytes plus the {@link
 * EffectiveTemplateIdentity} of the template they were generated from. The identity is the anchor
 * CR-3's pin records; in this change the bytes are stored as the draft and the identity is not yet
 * consumed.
 *
 * <p>The PDF bytes carry party PII and are never logged.
 */
public record DocumentProjectionResult(byte[] pdf, EffectiveTemplateIdentity identity) {}

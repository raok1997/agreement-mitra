package in.agreementmitra.documents.api;

/**
 * A browse-list item of the template catalog: system-owned metadata only. Carries the entry's id,
 * name, description, category {@code type}, {@code state} dimension, reserved {@code language}, and
 * {@code version} -- no template body, no {@code layerSetRef} pointer (internal), no status (only
 * published entries are ever listed), and no signer PII or secret.
 */
public record TemplateSummary(
    String id,
    String name,
    String description,
    String type,
    String state,
    String language,
    int version) {}

package in.agreementmitra.documents.api;

/**
 * The detail of one <b>published</b> catalog entry, returned by {@code GET /api/templates/{id}}.
 * System-owned metadata only, including the entry's {@link Dimensions} ({@code state}, {@code
 * type}, reserved {@code language}) and {@code version} -- the coordinates a capture flow carries
 * forward. Carries no template body, no internal pointer, and no signer PII or secret.
 */
public record TemplateDetail(
    String id, String name, String description, Dimensions dimensions, int version) {

  /** The selection coordinates of a catalog entry: {@code (state, type)} plus reserved language. */
  public record Dimensions(String state, String type, String language) {}
}

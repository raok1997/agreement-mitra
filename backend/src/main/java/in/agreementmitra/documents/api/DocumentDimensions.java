package in.agreementmitra.documents.api;

/**
 * The {@code (state, type)} selection coordinates a document projection resolves against. Optional
 * on a {@link DocumentProjectionRequest}: when omitted the projection resolves a default {@code
 * (state, type)} (the reference base layer's own dimensions). System-owned selection metadata --
 * carries no user data.
 */
public record DocumentDimensions(String state, String type) {}

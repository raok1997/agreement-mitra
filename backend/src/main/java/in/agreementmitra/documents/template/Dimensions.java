package in.agreementmitra.documents.template;

/**
 * Data-independent selection coordinates of a definition: the {@code (state, type)} point it is the
 * template for. Opaque strings here; the layered resolver (a follow-on CR) gives them meaning.
 */
record Dimensions(String state, String type) {}

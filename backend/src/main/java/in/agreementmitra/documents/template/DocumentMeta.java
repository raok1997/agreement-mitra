package in.agreementmitra.documents.template;

/**
 * Optional document-header metadata carried on a definition's {@link Meta}: the system-authored
 * {@code title}, {@code subtitle}, and {@code executionLine} text shown at the top of the rendered
 * agreement. {@code title} is required when the block is present; {@code subtitle} and {@code
 * executionLine} are nullable. {@code executionLine} is plain text with {@code {{slot}}} fills,
 * each naming a declared field; the slots are validated here (at definition time) but interpolated
 * and HTML-escaped only at compile time.
 */
record DocumentMeta(String title, String subtitle, String executionLine) {}

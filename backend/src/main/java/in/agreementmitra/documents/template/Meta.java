package in.agreementmitra.documents.template;

/**
 * Identity and selection metadata of a definition. {@code id} + {@code version} are the
 * human-facing coordinates; {@code dimensions} select the {@code (state, type)} point; {@code
 * status} is its lifecycle stage; {@code document} is the optional document-header block (title /
 * subtitle / execution line), {@code null} when the definition declares no header.
 */
record Meta(
    String id, Dimensions dimensions, int version, TemplateStatus status, DocumentMeta document) {}

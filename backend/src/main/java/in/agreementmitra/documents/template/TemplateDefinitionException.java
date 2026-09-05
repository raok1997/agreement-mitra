package in.agreementmitra.documents.template;

/**
 * Raised on any structural or semantic failure while loading a definition. Loading is
 * reject-or-nothing: this is thrown and <b>no</b> model is returned, never a partial one.
 *
 * <p>The message identifies the fault by <b>structural location only</b> -- a JSON pointer, field
 * {@code key}, clause {@code id}, or section title/entry -- and carries <b>no data value</b>, so
 * there is nothing sensitive to redact.
 */
final class TemplateDefinitionException extends RuntimeException {

  TemplateDefinitionException(String message) {
    super(message);
  }
}

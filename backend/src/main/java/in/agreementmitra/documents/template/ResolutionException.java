package in.agreementmitra.documents.template;

/**
 * Raised on any failure while loading a layer patch or resolving an effective template: a malformed
 * patch, an operation targeting a non-existent element, a removal that orphans a surviving slot or
 * section entry, a composed result that violates a definition invariant, or a {@code showWhen} that
 * fails to parse or references an undeclared field. Resolution is <b>reject-or-nothing</b>: this is
 * thrown and <b>no</b> effective template is returned, never a partially composed one.
 *
 * <p>Like {@link TemplateDefinitionException}, the message identifies the fault by <b>structural
 * location only</b> -- a layer id, JSON pointer, field {@code key}, clause {@code id}, or section
 * title/entry -- and carries <b>no data value</b>, so there is nothing sensitive to redact.
 */
sealed class ResolutionException extends RuntimeException
    permits ResolutionException.NoPublishedTemplate {

  ResolutionException(String message) {
    super(message);
  }

  /**
   * No published template exists for the requested dimensions -- the one <b>data</b> condition
   * among resolution failures. Every other {@link ResolutionException} is a defect in a template or
   * its catalog pointer.
   */
  static final class NoPublishedTemplate extends ResolutionException {

    NoPublishedTemplate(String message) {
      super(message);
    }
  }
}

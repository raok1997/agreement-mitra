package in.agreementmitra.documents;

/**
 * Thrown when a render cannot be produced -- the Gotenberg service is unreachable, times out, or
 * returns no document. Part of the {@code documents} module's public API so a caller (the signing
 * preview/generate flow) can distinguish a render failure from a bad request. Carries no rendered
 * content -- never a snippet of the HTML or the PDF bytes.
 */
public class DocumentRenderException extends RuntimeException {

  public DocumentRenderException(String message) {
    super(message);
  }

  public DocumentRenderException(String message, Throwable cause) {
    super(message, cause);
  }
}

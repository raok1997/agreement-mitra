package in.agreementmitra.signing;

/**
 * One attachment on an outbound {@link EmailMessage}: the filename the recipient sees, its content
 * type, and the raw bytes. Vendor-neutral by construction - nothing here names a provider.
 *
 * <p>The bytes are the document itself and are <b>never logged</b>; {@link #toString()} renders the
 * filename and a length only, so an accidental interpolation cannot dump a signed agreement into a
 * log file.
 *
 * @param filename the name presented to the recipient (never a storage key)
 * @param contentType the MIME type of {@link #content}
 * @param content the raw, unencoded bytes; the adapter performs any transfer encoding
 */
public record EmailAttachment(String filename, String contentType, byte[] content) {

  /** The raw byte count, which is what the attachment ceiling is measured against. */
  public int size() {
    return content == null ? 0 : content.length;
  }

  /** Filename + size only. Never the bytes. */
  @Override
  public String toString() {
    return "EmailAttachment{filename=" + filename + ", bytes=" + size() + "}";
  }
}

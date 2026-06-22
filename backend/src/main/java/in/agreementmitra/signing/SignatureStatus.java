package in.agreementmitra.signing;

/**
 * Lifecycle of a signing request. The active path is {@code PDF_GENERATED → STAMPED →
 * SIGN_REQUESTED → SIGNED | FAILED | EXPIRED}, with {@code STAMP_FAILED} as a terminal branch off
 * the stamp step. {@code DRAFT} is reserved (not yet used). {@code STAMPED} means a stamp is
 * confirmed attached to this request's instrument (freshly procured or reused).
 */
public enum SignatureStatus {
  DRAFT,
  PDF_GENERATED,
  STAMPED,
  SIGN_REQUESTED,
  SIGNED,
  FAILED,
  EXPIRED,
  STAMP_FAILED
}

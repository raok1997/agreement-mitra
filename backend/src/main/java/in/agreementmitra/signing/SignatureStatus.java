package in.agreementmitra.signing;

/** Lifecycle of a signing request. Transitions are linear from DRAFT. */
public enum SignatureStatus {
  DRAFT,
  PDF_GENERATED,
  SIGN_REQUESTED,
  SIGNED,
  FAILED,
  EXPIRED
}

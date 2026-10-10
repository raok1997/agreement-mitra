package in.agreementmitra.signing;

/**
 * What became of a gateway payment confirmation at the agreement. A seam type shared by the {@code
 * agreement} and {@code payment} sub-packages, like {@link PaymentConfirmation}.
 */
public enum PaymentRecording {

  /** The agreement held no payment; this one is now its recorded payment. */
  RECORDED,

  /** The agreement is already paid under this same reference; nothing was written. */
  ALREADY_RECORDED,

  /**
   * The agreement is already paid under another reference (or none); nothing was written and the
   * first payment's record stands. This payment is a second charge for staff to check.
   */
  SURPLUS
}

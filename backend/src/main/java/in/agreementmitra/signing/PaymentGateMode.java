package in.agreementmitra.signing;

/**
 * How strictly the payment gate is enforced. Set by configuration ({@code payment.mode}); switching
 * modes needs no code change and no schema change (design D5).
 *
 * <p>{@link #REQUIRED} is fully specified and tested now even though production runs {@link
 * #OPTIONAL}: the expensive part of adding payment later is not the gateway call, it is discovering
 * where the pipeline should have blocked. Settling that while the pipeline is being built makes
 * onboarding a gateway an adapter plus a config flip.
 */
public enum PaymentGateMode {

  /**
   * The default, and the only mode usable today (no payment gateway exists). The gate records
   * payment state and permits the pipeline to proceed whatever that state is.
   */
  OPTIONAL,

  /** The gate blocks the pipeline unless payment state is {@code PAID} or {@code WAIVED}. */
  REQUIRED
}

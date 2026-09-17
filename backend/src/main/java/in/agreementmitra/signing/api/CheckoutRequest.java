package in.agreementmitra.signing.api;

/**
 * The customer's stamp choice when starting checkout (state-stamp-duty-quoting, design D7). Carries
 * a <b>choice</b>, never an amount: {@code stampValueMinorUnits} must equal one of the options the
 * server recomputes, and the payable total is computed on the server from it. There is deliberately
 * no total, fee, currency or discount field.
 *
 * @param stampValueMinorUnits the chosen stamp value in paise; ignored when an order already exists
 * @param underStampAcknowledgement required exactly when the chosen value is below the legal duty
 */
public record CheckoutRequest(
    Long stampValueMinorUnits, Acknowledgement underStampAcknowledgement) {

  /** The customer acknowledged this version of the under-stamping warning. */
  public record Acknowledgement(String warningVersion) {}
}

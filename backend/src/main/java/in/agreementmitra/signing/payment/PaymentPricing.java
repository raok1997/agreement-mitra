package in.agreementmitra.signing.payment;

import org.springframework.stereotype.Service;

/**
 * The <b>pricing operation</b>: what an agreement costs, as integer minor units plus an explicit
 * currency.
 *
 * <p>Implements the published rule (TERMS-OF-SERVICE section 7, state-stamp-duty-quoting design
 * D6): <b>total = base fee + max(0, chosen stamp value - included stamp value)</b> -- INR 499 plus
 * the amount by which the stamp value exceeds INR 100. The stamp value is the option the customer
 * chose from the server-recomputed stamp options, never a client-supplied amount.
 *
 * <p>The amount is <b>never</b> taken from the client. No request DTO on the payment surface has an
 * amount, currency, or discount field, so there is nothing to ignore: a tampered client cannot
 * change what is charged or what is credited.
 */
@Service
class PaymentPricing {

  private final PaymentProperties properties;

  PaymentPricing(PaymentProperties properties) {
    this.properties = properties;
  }

  /**
   * The payable total for a chosen stamp value.
   *
   * @param stampValueMinorUnits a value already validated as one of the agreement's stamp options
   */
  Money price(long stampValueMinorUnits) {
    if (stampValueMinorUnits < 0) {
      throw new IllegalArgumentException("stamp value must not be negative");
    }
    PaymentProperties.Fee fee = properties.fee();
    long excess = Math.max(0L, stampValueMinorUnits - fee.includedStampValueMinorUnits());
    return new Money(Math.addExact(fee.baseMinorUnits(), excess), fee.currency());
  }

  String currency() {
    return properties.fee().currency();
  }
}

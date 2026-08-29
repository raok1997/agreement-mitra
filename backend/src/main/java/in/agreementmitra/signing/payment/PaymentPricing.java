package in.agreementmitra.signing.payment;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The <b>pricing operation</b>: what an agreement costs, as integer minor units plus an explicit
 * currency.
 *
 * <p>It returns a single configured flat price today, and it is a named, testable operation anyway
 * (design D7). That is the whole point: when state-and-rent-dependent stamp duty arrives, it
 * changes <em>this calculation</em> and nothing else - not the order-creation flow, not the API
 * shape, not the stored record. The cost today is one indirection.
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
   * The payable amount for one agreement.
   *
   * <p>The agreement id is taken deliberately even though today's flat price ignores it: the
   * signature is the seam. A duty calculation that needs the state, the rent, and the term will
   * read them here, and every caller already passes the only thing it needs to.
   */
  Money priceFor(UUID agreementId) {
    PaymentProperties.Amount amount = properties.amount();
    return new Money(amount.minorUnits(), amount.currency());
  }
}

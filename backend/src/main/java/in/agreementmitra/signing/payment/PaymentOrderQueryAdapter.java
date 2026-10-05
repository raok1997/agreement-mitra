package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.PaymentOrderQuery;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts the package-private {@link PaymentOrderRepository} to the module-root {@link
 * PaymentOrderQuery} seam, the same shape as the signing-request query adapter.
 */
@Component
class PaymentOrderQueryAdapter implements PaymentOrderQuery {

  private final PaymentOrderRepository repository;

  PaymentOrderQueryAdapter(PaymentOrderRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean existsForAgreement(UUID agreementId) {
    return repository.countByAgreementId(agreementId) > 0;
  }
}

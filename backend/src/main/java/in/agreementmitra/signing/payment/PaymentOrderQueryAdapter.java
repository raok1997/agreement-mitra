package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SurplusPayment;
import java.time.Instant;
import java.util.List;
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

  @Override
  public List<UUID> agreementsWithOrderPaidSince(Instant cutoff) {
    return repository.findAgreementIdsPaidSince(cutoff);
  }

  @Override
  public List<SurplusPayment> surplusOrdersPaidSince(Instant cutoff) {
    return repository.findSurplusPaidSince(cutoff);
  }
}

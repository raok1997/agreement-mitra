package in.agreementmitra.signing.agreement;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create + fetch for the {@link Agreement} aggregate. Internal to the {@code signing} module; the
 * {@code api} controller is the only caller. Content is validated at the API boundary before it
 * reaches {@link #create}.
 */
@Service
public class AgreementService {

  private final AgreementRepository repository;

  public AgreementService(AgreementRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Agreement create(
      String landlordName,
      String tenantName,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      int termMonths) {
    return repository.save(
        Agreement.create(
            landlordName, tenantName, propertyAddress, monthlyRent, securityDeposit, termMonths));
  }

  @Transactional(readOnly = true)
  public Optional<Agreement> find(UUID id) {
    return repository.findById(id);
  }
}

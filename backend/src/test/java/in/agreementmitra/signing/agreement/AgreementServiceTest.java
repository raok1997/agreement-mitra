package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.CreateAgreementRequest;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-tests the entity→response mapping via the service with a mocked repository (no Spring, no
 * DB). {@code @Transactional} is a no-op outside a container, so this exercises the real mapping.
 */
@ExtendWith(MockitoExtension.class)
class AgreementServiceTest {

  @Mock private AgreementRepository repository;

  @InjectMocks private AgreementService service;

  @Test
  void createMapsAggregateToResponseWithSignerIdsRolesAndEmails() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "9 Park Street, Kolkata",
            new BigDecimal("18000.00"),
            new BigDecimal("36000.00"),
            11,
            List.of(
                new SignerRequest("Asha Owner", "asha@example.com", Role.OWNER),
                new SignerRequest("Tara Tenant", "tara@example.com", Role.TENANT)));
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    AgreementResponse response = service.create(request);

    assertThat(response.id()).isNotNull();
    assertThat(response.propertyAddress()).isEqualTo("9 Park Street, Kolkata");
    assertThat(response.monthlyRent()).isEqualByComparingTo("18000.00");
    assertThat(response.securityDeposit()).isEqualByComparingTo("36000.00");
    assertThat(response.termMonths()).isEqualTo(11);
    assertThat(response.createdAt()).isNotNull();
    assertThat(response.signers()).hasSize(2);
    assertThat(response.signers()).allSatisfy(s -> assertThat(s.id()).isNotNull());
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::email)
        .containsExactly("asha@example.com", "tara@example.com");
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::role)
        .containsExactly(Role.OWNER, Role.TENANT);
  }
}

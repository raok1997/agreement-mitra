package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.CreateAgreementRequest;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-tests the entity-to-response mapping via the service with a mocked repository (no Spring, no
 * DB). {@code @Transactional} is a no-op outside a container, so this exercises the real mapping,
 * the full-name derivation, and the date-driven duration.
 *
 * <p>Also covers catalog-selection at create (agreement-template-selection): a supplied {@code
 * (state, type)} resolves the published template via {@link TemplateCatalogApi} and records its id
 * server-side; an unknown pair is rejected with the no-oracle 404 and nothing is persisted; an
 * absent pair keeps the default (no selection).
 */
@ExtendWith(MockitoExtension.class)
class AgreementServiceTest {

  @Mock private AgreementRepository repository;
  @Mock private TemplateCatalogApi templateCatalog;

  @InjectMocks private AgreementService service;

  @Test
  void createMapsPartiesDerivesNameAndDuration() {
    CreateAgreementRequest request =
        new CreateAgreementRequest(
            "9 Park Street, Kolkata",
            new BigDecimal("18000.00"),
            new BigDecimal("36000.00"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1),
            List.of(
                // no name override -> derived "Asha Rao"
                new SignerRequest(
                    "Asha",
                    "Rao",
                    "Ravi Rao",
                    "1 A St",
                    "asha@example.com",
                    null,
                    null,
                    Role.OWNER),
                // name override honoured verbatim (Aadhaar spelling)
                new SignerRequest(
                    "Tara",
                    "Sen",
                    "Hari Sen",
                    "3 C St",
                    null,
                    "9000000000",
                    "Tara Kumari Sen",
                    Role.TENANT)));
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    AgreementResponse response = service.create(request);

    assertThat(response.id()).isNotNull();
    assertThat(response.propertyAddress()).isEqualTo("9 Park Street, Kolkata");
    assertThat(response.monthlyRent()).isEqualByComparingTo("18000.00");
    assertThat(response.securityDeposit()).isEqualByComparingTo("36000.00");
    assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    assertThat(response.durationMonths()).isEqualTo(11);
    assertThat(response.createdAt()).isNotNull();
    assertThat(response.signers()).hasSize(2);
    assertThat(response.signers()).allSatisfy(s -> assertThat(s.id()).isNotNull());
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::name)
        .containsExactly("Asha Rao", "Tara Kumari Sen");
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::email)
        .containsExactly("asha@example.com", null);
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::mobile)
        .containsExactly(null, "9000000000");
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::role)
        .containsExactly(Role.OWNER, Role.TENANT);
  }

  @Test
  void createResolvesAndRecordsThePublishedTemplateForSuppliedDimensions() {
    UUID templateId = UUID.randomUUID();
    when(templateCatalog.publishedTemplateIdFor("TG", "residential"))
        .thenReturn(Optional.of(templateId.toString()));
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.create(requestWithDimensions("TG", "residential"));

    ArgumentCaptor<Agreement> saved = ArgumentCaptor.forClass(Agreement.class);
    verify(repository).save(saved.capture());
    // The server records the catalog-resolved UUID (never a client-set one) on the aggregate.
    assertThat(saved.getValue().templateId()).isEqualTo(templateId);
  }

  @Test
  void createRejectsAnUnknownDimensionPairAndPersistsNothing() {
    when(templateCatalog.publishedTemplateIdFor("ZZ", "residential")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(requestWithDimensions("ZZ", "residential")))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(repository, never()).save(any());
  }

  @Test
  void createWithoutDimensionsKeepsTheDefaultAndNeverTouchesTheCatalog() {
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.create(requestWithDimensions(null, null));

    ArgumentCaptor<Agreement> saved = ArgumentCaptor.forClass(Agreement.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().templateId()).isNull();
    verifyNoInteractions(templateCatalog);
  }

  private static CreateAgreementRequest requestWithDimensions(String state, String type) {
    return new CreateAgreementRequest(
        "9 Park Street, Kolkata",
        new BigDecimal("18000.00"),
        new BigDecimal("36000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1),
        List.of(
            new SignerRequest(
                "Asha", "Rao", "Ravi Rao", "1 A St", "asha@example.com", null, null, Role.OWNER),
            new SignerRequest(
                "Tara", "Sen", "Hari Sen", "3 C St", null, "9000000000", null, Role.TENANT)),
        state,
        type);
  }
}

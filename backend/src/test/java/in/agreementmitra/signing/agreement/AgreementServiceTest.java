package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.CreateAgreementRequest;
import in.agreementmitra.signing.api.CreateAgreementRequest.SignerRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
  @Mock private SigningRequestQuery signingRequestQuery;

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

  // --- Capture state (M5): stored on create/update; server-managed keys stripped (D2/D4) ---

  @Test
  void createStoresTheCaptureStateOnTheAggregate() {
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.create(
        requestWithCapture(
            Map.of("lockInMonths", "6", "petAllowed", "true"), List.of("Pets", "Lock-in")));

    ArgumentCaptor<Agreement> saved = ArgumentCaptor.forClass(Agreement.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().captureState()).isNotNull();
    assertThat(saved.getValue().captureState().data())
        .containsEntry("lockInMonths", "6")
        .containsEntry("petAllowed", "true");
    assertThat(saved.getValue().captureState().activeSections()).containsExactly("Pets", "Lock-in");
  }

  @Test
  void createStripsServerManagedKeysFromTheCaptureMap() {
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    // A malicious/oversharing map carrying server-managed keys: they must never round-trip.
    Map<String, String> hostile = new java.util.HashMap<>();
    hostile.put("id", UUID.randomUUID().toString());
    hostile.put("ownerIdentityId", UUID.randomUUID().toString());
    hostile.put("createdAt", "1999-01-01T00:00:00Z");
    hostile.put("durationMonths", "999");
    hostile.put("templateContentHash", "sha256:evil");
    hostile.put("templateLayerVersions", "{}");
    hostile.put("lockInMonths", "6"); // the one legitimate user field

    service.create(requestWithCapture(hostile, List.of()));

    ArgumentCaptor<Agreement> saved = ArgumentCaptor.forClass(Agreement.class);
    verify(repository).save(saved.capture());
    // Only the legitimate user field survives; every server-managed key was stripped.
    assertThat(saved.getValue().captureState().data()).containsOnlyKeys("lockInMonths");
    // Server-managed fields stay server-managed (never derived from the map).
    assertThat(saved.getValue().ownerIdentityId()).isNull();
    assertThat(saved.getValue().templateContentHash()).isNull();
    assertThat(saved.getValue().termMonths()).isEqualTo(11); // derived from dates, not the map
  }

  @Test
  void createWithNoCaptureDataPersistsNullCaptureState() {
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.create(minimalRequest()); // no captureData / activeSections

    ArgumentCaptor<Agreement> saved = ArgumentCaptor.forClass(Agreement.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().captureState()).isNull();
  }

  @Test
  void updateReplacesTheCaptureStateWholesaleAndStripsServerManagedKeys() {
    UUID owner = UUID.randomUUID();
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(owner);
    agreement.replaceCaptureState(Map.of("stale", "old"), List.of("Old")); // prior state
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
    when(signingRequestQuery.existsForAgreement(agreement.getId())).thenReturn(false);
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    Map<String, String> newData = new java.util.HashMap<>();
    newData.put("lockInMonths", "12");
    newData.put("ownerIdentityId", UUID.randomUUID().toString()); // stripped

    service.update(
        agreement.getId(),
        owner,
        new CreateAgreementRequest(
            "12 New Road, Pune",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            LocalDate.of(2027, 3, 1),
            LocalDate.of(2028, 3, 1),
            List.of(
                new SignerRequest(
                    "Meera",
                    "Iyer",
                    "Raj Iyer",
                    "5 D St",
                    "meera@example.com",
                    null,
                    null,
                    Role.OWNER),
                new SignerRequest(
                    "Nikhil",
                    "Rao",
                    "Ram Rao",
                    "6 E St",
                    "nikhil@example.com",
                    null,
                    null,
                    Role.TENANT)),
            null,
            null,
            newData,
            List.of("Lock-in")));

    // Wholesale replace: the prior "Old" section + "stale" key are gone; server-managed key
    // stripped.
    assertThat(agreement.captureState().data()).containsOnlyKeys("lockInMonths");
    assertThat(agreement.captureState().activeSections()).containsExactly("Lock-in");
  }

  // --- Claim (D2): unowned -> owned; same owner idempotent; other owner -> 404 (no oracle) ---

  @Test
  void claimSetsOwnerOnAnUnownedAgreement() {
    Agreement agreement = anUnownedAgreement();
    UUID owner = UUID.randomUUID();
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.claim(agreement.getId(), owner);

    assertThat(agreement.ownerIdentityId()).isEqualTo(owner);
    verify(repository).save(agreement);
  }

  @Test
  void claimIsIdempotentForTheSameOwner() {
    UUID owner = UUID.randomUUID();
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(owner); // already owned by this identity
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.claim(agreement.getId(), owner); // re-claim -> no-op, no throw

    assertThat(agreement.ownerIdentityId()).isEqualTo(owner);
  }

  @Test
  void claimByAnotherOwnerIsRejectedAsNotFound() {
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(UUID.randomUUID()); // owned by someone else
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));

    assertThatThrownBy(() -> service.claim(agreement.getId(), UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void claimOfAnUnknownAgreementIsNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.claim(id, UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // --- Edit (D4): owner + pre-signing-request only; frozen -> 409; parties replaced wholesale ---

  @Test
  void updateReplacesTermsAndPartiesAndClearsTheDraftWhenOwnerAndNotFrozen() {
    UUID owner = UUID.randomUUID();
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(owner);
    agreement.attachDraft("drafts/x.pdf");
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
    when(signingRequestQuery.existsForAgreement(agreement.getId())).thenReturn(false);
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    AgreementResponse response =
        service.update(
            agreement.getId(),
            owner,
            new CreateAgreementRequest(
                "12 New Road, Pune",
                new BigDecimal("25000.00"),
                new BigDecimal("50000.00"),
                LocalDate.of(2027, 3, 1),
                LocalDate.of(2028, 3, 1),
                List.of(
                    new SignerRequest(
                        "Meera",
                        "Iyer",
                        "Raj Iyer",
                        "5 D St",
                        "meera@example.com",
                        null,
                        null,
                        Role.OWNER))));

    assertThat(response.propertyAddress()).isEqualTo("12 New Road, Pune");
    assertThat(response.durationMonths()).isEqualTo(12);
    assertThat(response.signers())
        .extracting(AgreementResponse.SignerResponse::name)
        .containsExactly("Meera Iyer"); // wholesale replace -- the prior two parties are gone
    assertThat(agreement.draftPdfKey()).isNull(); // pinned draft cleared
  }

  @Test
  void updateIsBlockedOnceASigningRequestExists() {
    UUID owner = UUID.randomUUID();
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(owner);
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
    when(signingRequestQuery.existsForAgreement(agreement.getId())).thenReturn(true);

    assertThatThrownBy(() -> service.update(agreement.getId(), owner, minimalRequest()))
        .isInstanceOf(ConflictException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void updateByANonOwnerIsNotFound() {
    UUID owner = UUID.randomUUID();
    Agreement agreement = anUnownedAgreement();
    agreement.claimBy(owner);
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));

    assertThatThrownBy(() -> service.update(agreement.getId(), UUID.randomUUID(), minimalRequest()))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(repository, never()).save(any());
  }

  private static Agreement anUnownedAgreement() {
    return Agreement.create(
        "9 Park Street, Kolkata",
        new BigDecimal("18000.00"),
        new BigDecimal("36000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1));
  }

  private static CreateAgreementRequest minimalRequest() {
    return new CreateAgreementRequest(
        "9 Park Street, Kolkata",
        new BigDecimal("18000.00"),
        new BigDecimal("36000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1),
        List.of(
            new SignerRequest(
                "Asha", "Rao", "Ravi Rao", "1 A St", "asha@example.com", null, null, Role.OWNER)));
  }

  private static CreateAgreementRequest requestWithCapture(
      Map<String, String> captureData, List<String> activeSections) {
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
        null,
        null,
        captureData,
        activeSections);
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

  // --- staff fulfilment queue projection (staff-queue-fulfilment-context) ---------------------
  //
  // The queue row is what an operator fills the vendor's certificate form from, so what it carries
  // is behaviour, not presentation: the parties in a stable order, and the state of the template
  // the
  // instrument was drafted against.

  private static Agreement agreementWith(UUID templateId, Object... signers) {
    Agreement agreement =
        Agreement.create(
            "9 Park Street, Kolkata",
            new BigDecimal("18000.00"),
            new BigDecimal("36000.00"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    for (int i = 0; i < signers.length; i += 3) {
      agreement.addSigner(
          (String) signers[i],
          "First",
          "Last",
          (String) signers[i + 1],
          "1 A St",
          null,
          null,
          (Role) signers[i + 2]);
    }
    if (templateId != null) {
      agreement.selectTemplate(templateId);
    }
    return agreement;
  }

  private static TemplateDetail publishedTemplate(UUID id, String name, String state) {
    return new TemplateDetail(
        id.toString(), name, "desc", new TemplateDetail.Dimensions(state, "rental", "en"), 1);
  }

  @Test
  void staffViewListsEveryPartyOwnersFirstWithFatherNames() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement =
        agreementWith(
            templateId,
            // deliberately tenant-first in stored order, to prove the projection reorders
            "Bilal Khan",
            "Imran Khan",
            Role.TENANT,
            "Asha Rao",
            "Krishna Rao",
            Role.OWNER,
            "Meera Rao",
            "Krishna Rao",
            Role.OWNER);
    when(repository.findAllById(List.of(agreement.getId()))).thenReturn(List.of(agreement));
    when(templateCatalog.find(templateId.toString()))
        .thenReturn(Optional.of(publishedTemplate(templateId, "Karnataka Rental Agreement", "KA")));

    StaffAgreementView view =
        service.staffViewsByAgreementId(List.of(agreement.getId())).get(agreement.getId());

    // Owners first; within a role, the order the aggregate stored them in.
    assertThat(view.parties())
        .extracting(StaffPartyView::role, StaffPartyView::name, StaffPartyView::fatherName)
        .containsExactly(
            tuple(Role.OWNER, "Asha Rao", "Krishna Rao"),
            tuple(Role.OWNER, "Meera Rao", "Krishna Rao"),
            tuple(Role.TENANT, "Bilal Khan", "Imran Khan"));
  }

  @Test
  void staffViewTakesTheStateFromThePinnedTemplateNotTheAddress() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementWith(templateId, "Asha Rao", "Krishna Rao", Role.OWNER);
    when(repository.findAllById(List.of(agreement.getId()))).thenReturn(List.of(agreement));
    when(templateCatalog.find(templateId.toString()))
        .thenReturn(Optional.of(publishedTemplate(templateId, "Telangana Rental Agreement", "TG")));

    StaffAgreementView view =
        service.staffViewsByAgreementId(List.of(agreement.getId())).get(agreement.getId());

    // The address ends in "Kolkata"; the state is the template's, because stamp duty follows the
    // law the instrument was drafted under.
    assertThat(view.templateState()).isEqualTo("TG");
    assertThat(view.templateName()).isEqualTo("Telangana Rental Agreement");
    assertThat(view.propertyCity()).isEqualTo("Kolkata");
  }

  @Test
  void anUnresolvableTemplateLeavesTheRowOnTheQueueWithoutTemplateDetail() {
    UUID templateId = UUID.randomUUID();
    Agreement pinned = agreementWith(templateId, "Asha Rao", "Krishna Rao", Role.OWNER);
    Agreement unpinned = agreementWith(null, "Bilal Khan", "Imran Khan", Role.TENANT);
    when(repository.findAllById(any())).thenReturn(List.of(pinned, unpinned));
    // Superseded or archived since this agreement pinned it. find() reports that as empty rather
    // than throwing: an exception here would mark this transaction rollback-only and fail the whole
    // queue, catch or no catch.
    when(templateCatalog.find(templateId.toString())).thenReturn(Optional.empty());

    Map<UUID, StaffAgreementView> views =
        service.staffViewsByAgreementId(List.of(pinned.getId(), unpinned.getId()));

    // Both rows survive -- a catalog lifecycle event must not hide outstanding work.
    assertThat(views).containsKeys(pinned.getId(), unpinned.getId());
    assertThat(views.get(pinned.getId()).templateName()).isNull();
    assertThat(views.get(pinned.getId()).templateState()).isNull();
    assertThat(views.get(unpinned.getId()).templateName()).isNull();
    // The parties are still there: the operator can still identify the instrument.
    assertThat(views.get(pinned.getId()).parties()).hasSize(1);
  }

  @Test
  void templatesAreResolvedOncePerDistinctTemplateNotOncePerRow() {
    UUID templateId = UUID.randomUUID();
    Agreement first = agreementWith(templateId, "Asha Rao", "Krishna Rao", Role.OWNER);
    Agreement second = agreementWith(templateId, "Bilal Khan", "Imran Khan", Role.TENANT);
    Agreement third = agreementWith(templateId, "Chetan Iyer", "Ganesh Iyer", Role.OWNER);
    when(repository.findAllById(any())).thenReturn(List.of(first, second, third));
    when(templateCatalog.find(templateId.toString()))
        .thenReturn(Optional.of(publishedTemplate(templateId, "Karnataka Rental Agreement", "KA")));

    service.staffViewsByAgreementId(List.of(first.getId(), second.getId(), third.getId()));

    // Three rows, one template: one lookup. A per-row call would be an N+1 against the catalog.
    verify(templateCatalog, times(1)).find(templateId.toString());
  }
}

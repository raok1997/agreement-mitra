package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-domain unit tests for the {@link Agreement} aggregate — no Spring, no DB. */
class AgreementTest {

  private static Agreement newAgreement() {
    return Agreement.create(
        "12 MG Road, Bengaluru",
        new BigDecimal("25000.00"),
        BigDecimal.ZERO,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1));
  }

  @Test
  void factoryAssignsIdCreatedAtAndIsNew() {
    Agreement agreement = newAgreement();

    assertThat(agreement.getId()).isNotNull();
    assertThat(agreement.createdAt()).isNotNull();
    assertThat(agreement.isNew()).isTrue();
    assertThat(agreement.signers()).isEmpty();
  }

  @Test
  void factoryDerivesWholeMonthTermFromDates() {
    Agreement agreement = newAgreement();

    assertThat(agreement.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(agreement.endDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    assertThat(agreement.termMonths()).isEqualTo(11);
  }

  @Test
  void markNotNewFlipsIsNew() {
    Agreement agreement = newAgreement();

    agreement.markNotNew();

    assertThat(agreement.isNew()).isFalse();
  }

  @Test
  void equalsAndHashCodeAreIdBased() {
    Agreement a = newAgreement();
    Agreement b = newAgreement();

    assertThat(a).isEqualTo(a).isNotEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(a.getId().hashCode());
  }

  @Test
  void addSignerWiresBothSidesAndRetainsMultipleParties() {
    Agreement agreement = newAgreement();

    agreement.addSigner(
        "Asha Owner",
        "Asha",
        "Owner",
        "Ravi Owner",
        "1 A St",
        "asha@example.com",
        null,
        Role.OWNER);
    agreement.addSigner(
        "Bhanu Owner",
        "Bhanu",
        "Owner",
        "Gopi Owner",
        "2 B St",
        "bhanu@example.com",
        null,
        Role.OWNER);
    agreement.addSigner(
        "Tara Tenant", "Tara", "Tenant", "Hari Tenant", "3 C St", null, "9000000000", Role.TENANT);

    assertThat(agreement.signers()).hasSize(3);
    assertThat(agreement.signers())
        .extracting(Signer::role)
        .containsExactly(Role.OWNER, Role.OWNER, Role.TENANT);
    assertThat(agreement.signers()).allSatisfy(s -> assertThat(s.id()).isNotNull());
  }

  @Test
  void freshAgreementHasNullPin() {
    Agreement agreement = newAgreement();

    assertThat(agreement.templateContentHash()).isNull();
    assertThat(agreement.templateLayerVersions()).isNull();
  }

  @Test
  void pinEffectiveTemplateRecordsHashAndLayerVersions() {
    Agreement agreement = newAgreement();

    agreement.pinEffectiveTemplate("sha256:abc123", Map.of("base", 3, "in-residential", 1));

    assertThat(agreement.templateContentHash()).isEqualTo("sha256:abc123");
    assertThat(agreement.templateLayerVersions())
        .containsExactlyInAnyOrderEntriesOf(Map.of("base", 3, "in-residential", 1));
  }

  @Test
  void pinEffectiveTemplateDefensivelyCopiesAndExposesAnUnmodifiableMap() {
    Agreement agreement = newAgreement();
    Map<String, Integer> source = new HashMap<>(Map.of("base", 3));

    agreement.pinEffectiveTemplate("sha256:abc123", source);
    source.put("base", 99); // mutating the caller's map must not leak into the pin

    assertThat(agreement.templateLayerVersions()).containsEntry("base", 3);
    assertThatThrownBy(() -> agreement.templateLayerVersions().put("base", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void freshAgreementIsUnowned() {
    assertThat(newAgreement().ownerIdentityId()).isNull();
  }

  @Test
  void claimBySetsOwnerWhenUnowned() {
    Agreement agreement = newAgreement();
    UUID owner = UUID.randomUUID();

    agreement.claimBy(owner);

    assertThat(agreement.ownerIdentityId()).isEqualTo(owner);
  }

  @Test
  void claimByTheSameOwnerIsIdempotent() {
    Agreement agreement = newAgreement();
    UUID owner = UUID.randomUUID();
    agreement.claimBy(owner);

    agreement.claimBy(owner); // no throw, still the same owner

    assertThat(agreement.ownerIdentityId()).isEqualTo(owner);
  }

  @Test
  void claimByADifferentOwnerIsRejected() {
    Agreement agreement = newAgreement();
    agreement.claimBy(UUID.randomUUID());

    assertThatThrownBy(() -> agreement.claimBy(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void replaceTermsRewritesTermsAndRederivesTheMonthTerm() {
    Agreement agreement = newAgreement();

    agreement.replaceTerms(
        "5 New Ave, Chennai",
        new BigDecimal("30000.00"),
        new BigDecimal("60000.00"),
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2028, 1, 1));

    assertThat(agreement.propertyAddress()).isEqualTo("5 New Ave, Chennai");
    assertThat(agreement.monthlyRent()).isEqualByComparingTo("30000.00");
    assertThat(agreement.termMonths()).isEqualTo(12);
  }

  @Test
  void clearSignersEmptiesTheParties() {
    Agreement agreement = newAgreement();
    agreement.addSigner(
        "Asha Owner",
        "Asha",
        "Owner",
        "Ravi Owner",
        "1 A St",
        "asha@example.com",
        null,
        Role.OWNER);

    agreement.clearSigners();

    assertThat(agreement.signers()).isEmpty();
  }

  @Test
  void clearDraftPinClearsDraftKeyAndReproducibilityPin() {
    Agreement agreement = newAgreement();
    agreement.attachDraft("drafts/a.pdf");
    agreement.pinEffectiveTemplate("sha256:abc", Map.of("base", 1));

    agreement.clearDraftPin();

    assertThat(agreement.draftPdfKey()).isNull();
    assertThat(agreement.templateContentHash()).isNull();
    assertThat(agreement.templateLayerVersions()).isNull();
  }

  // --- Capture state (M5): stored + defensively copied; null-safe; wholesale replace (D1/D4) ---

  @Test
  void freshAgreementHasNoCaptureState() {
    assertThat(newAgreement().captureState()).isNull();
  }

  @Test
  void replaceCaptureStateStoresTheMapAndActiveSections() {
    Agreement agreement = newAgreement();

    agreement.replaceCaptureState(
        Map.of("lockInMonths", "6", "petAllowed", "true"), List.of("Pets", "Lock-in"));

    assertThat(agreement.captureState()).isNotNull();
    assertThat(agreement.captureState().data())
        .containsEntry("lockInMonths", "6")
        .containsEntry("petAllowed", "true");
    assertThat(agreement.captureState().activeSections()).containsExactly("Pets", "Lock-in");
  }

  @Test
  void replaceCaptureStateDefensivelyCopiesAndExposesUnmodifiableCollections() {
    Agreement agreement = newAgreement();
    Map<String, String> data = new HashMap<>(Map.of("lockInMonths", "6"));
    List<String> sections = new ArrayList<>(List.of("Pets"));

    agreement.replaceCaptureState(data, sections);
    data.put("lockInMonths", "99"); // mutating the caller's inputs must not leak into the state
    sections.add("Parking");

    assertThat(agreement.captureState().data()).containsEntry("lockInMonths", "6").hasSize(1);
    assertThat(agreement.captureState().activeSections()).containsExactly("Pets");
    assertThatThrownBy(() -> agreement.captureState().data().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> agreement.captureState().activeSections().add("z"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void replaceCaptureStateWithNoDataAndNoSectionsCollapsesToNull() {
    Agreement agreement = newAgreement();
    agreement.replaceCaptureState(Map.of("lockInMonths", "6"), List.of("Pets"));

    agreement.replaceCaptureState(null, null); // wholesale replace clears it
    assertThat(agreement.captureState()).isNull();

    agreement.replaceCaptureState(Map.of(), List.of()); // empty is also "none"
    assertThat(agreement.captureState()).isNull();
  }

  @Test
  void replaceCaptureStateReplacesWholesale() {
    Agreement agreement = newAgreement();
    agreement.replaceCaptureState(Map.of("a", "1", "b", "2"), List.of("Pets"));

    agreement.replaceCaptureState(Map.of("c", "3"), List.of("Parking"));

    assertThat(agreement.captureState().data()).containsOnlyKeys("c");
    assertThat(agreement.captureState().activeSections()).containsExactly("Parking");
  }

  @Test
  void signersCollectionIsUnmodifiable() {
    Agreement agreement = newAgreement();
    agreement.addSigner(
        "Asha Owner",
        "Asha",
        "Owner",
        "Ravi Owner",
        "1 A St",
        "asha@example.com",
        null,
        Role.OWNER);

    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> agreement.signers().clear());
  }
}

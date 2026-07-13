package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
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

package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure-domain unit tests for the {@link Agreement} aggregate — no Spring, no DB. */
class AgreementTest {

  private static Agreement newAgreement() {
    return Agreement.create(
        "12 MG Road, Bengaluru", new BigDecimal("25000.00"), BigDecimal.ZERO, 11);
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

    agreement.addSigner("Asha Owner", "asha@example.com", Role.OWNER);
    agreement.addSigner("Bhanu Owner", "bhanu@example.com", Role.OWNER);
    agreement.addSigner("Tara Tenant", "tara@example.com", Role.TENANT);

    assertThat(agreement.signers()).hasSize(3);
    assertThat(agreement.signers())
        .extracting(Signer::role)
        .containsExactly(Role.OWNER, Role.OWNER, Role.TENANT);
    assertThat(agreement.signers()).allSatisfy(s -> assertThat(s.id()).isNotNull());
  }

  @Test
  void signersCollectionIsUnmodifiable() {
    Agreement agreement = newAgreement();
    agreement.addSigner("Asha Owner", "asha@example.com", Role.OWNER);

    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> agreement.signers().clear());
  }
}

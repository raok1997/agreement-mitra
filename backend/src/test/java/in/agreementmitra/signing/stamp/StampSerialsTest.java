package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic synthetic stamp-serial generator. */
class StampSerialsTest {

  @Test
  void sameAgreementYieldsTheSameSerial() {
    UUID id = UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
    assertThat(StampSerials.forAgreement(id)).isEqualTo(StampSerials.forAgreement(id));
  }

  @Test
  void differentAgreementsYieldDifferentSerials() {
    String a = StampSerials.forAgreement(UUID.randomUUID());
    String b = StampSerials.forAgreement(UUID.randomUUID());
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void serialUsesTheSyntheticBwSeriesFormat() {
    assertThat(StampSerials.forAgreement(UUID.randomUUID())).matches("BW \\d{10}");
  }
}

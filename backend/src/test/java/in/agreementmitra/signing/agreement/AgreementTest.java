package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the aggregate factory — no Spring context, no I/O. */
class AgreementTest {

  @Test
  void factoryAssignsIdentityAndTimestampAndStoresFields() {
    Agreement a =
        Agreement.create(
            "Asha Landlord",
            "Ravi Tenant",
            "12 MG Road, Bengaluru 560001",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            11);

    assertThat(a.getId()).isNotNull();
    assertThat(a.getCreatedAt()).isNotNull();
    assertThat(a.getLandlordName()).isEqualTo("Asha Landlord");
    assertThat(a.getTenantName()).isEqualTo("Ravi Tenant");
    assertThat(a.getPropertyAddress()).isEqualTo("12 MG Road, Bengaluru 560001");
    assertThat(a.getMonthlyRent()).isEqualByComparingTo("25000.00");
    assertThat(a.getSecurityDeposit()).isEqualByComparingTo("50000.00");
    assertThat(a.getTermMonths()).isEqualTo(11);
  }

  @Test
  void eachAgreementGetsADistinctIdentity() {
    Agreement first = sample();
    Agreement second = sample();

    assertThat(first.getId()).isNotEqualTo(second.getId());
    assertThat(first).isNotEqualTo(second);
    assertThat(first).isEqualTo(first);
  }

  private static Agreement sample() {
    return Agreement.create(
        "Asha Landlord",
        "Ravi Tenant",
        "12 MG Road, Bengaluru 560001",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        11);
  }
}

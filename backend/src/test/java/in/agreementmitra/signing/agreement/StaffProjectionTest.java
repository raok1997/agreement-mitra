package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The staff fulfilment projection carries personal data on purpose, so the two guarantees that keep
 * it contained are tested rather than left to convention: it never prints a name, and it never
 * grows a field nobody decided to add.
 */
class StaffProjectionTest {

  private static final StaffPartyView OWNER =
      new StaffPartyView(Role.OWNER, "Asha Rao", "Krishna Rao");
  private static final StaffPartyView TENANT =
      new StaffPartyView(Role.TENANT, "Bilal Khan", "Imran Khan");

  private static StaffAgreementView view() {
    return new StaffAgreementView(
        UUID.randomUUID(),
        "AM7K3QPW9Z4",
        "Bengaluru",
        LocalDate.of(2026, 1, 1),
        "Karnataka Residential Rental Agreement",
        "KA",
        List.of(OWNER, TENANT));
  }

  /**
   * A record's generated {@code toString} prints every component, so the default would put both
   * names into any log line that interpolates one. This is the language default, not carelessness -
   * which is exactly why it is asserted.
   */
  @Test
  void partyToStringCarriesTheRoleAndNeitherName() {
    String printed = OWNER.toString();

    assertThat(printed).contains("OWNER");
    assertThat(printed).doesNotContain("Asha Rao");
    assertThat(printed).doesNotContain("Krishna Rao");
  }

  @Test
  void agreementViewToStringCarriesNoPartyName() {
    StaffAgreementView view = view();

    String printed = view.toString();

    assertThat(printed).contains(view.trackingReference());
    assertThat(printed).doesNotContain("Asha Rao");
    assertThat(printed).doesNotContain("Krishna Rao");
    assertThat(printed).doesNotContain("Bilal Khan");
    assertThat(printed).doesNotContain("Imran Khan");
  }

  /**
   * The exclusion list is the contract, so it is pinned to the record's shape: adding a component
   * here fails until someone updates this list, which is the moment to ask whether purchasing an
   * e-stamp actually needs it. Contacts, rent, deposit and the full street address stay out.
   */
  @Test
  void theProjectionCarriesOnlyTheAgreedComponents() {
    assertThat(componentsOf(StaffAgreementView.class))
        .containsExactlyInAnyOrder(
            "agreementId",
            "trackingReference",
            "propertyCity",
            "agreementStartDate",
            "templateName",
            "templateState",
            "parties");

    assertThat(componentsOf(StaffPartyView.class))
        .containsExactlyInAnyOrder("role", "name", "fatherName");
  }

  @Test
  void thePartyListIsImmutable() {
    StaffAgreementView view = view();

    assertThat(view.parties()).isUnmodifiable();
  }

  private static List<String> componentsOf(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}

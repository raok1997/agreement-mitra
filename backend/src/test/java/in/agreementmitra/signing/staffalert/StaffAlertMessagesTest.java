package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.Role;
import in.agreementmitra.signing.agreement.StaffAgreementView;
import in.agreementmitra.signing.agreement.StaffPartyView;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What a staff alert may say: the tracking reference, a two-letter state, a secure site link. */
class StaffAlertMessagesTest {

  private static final UUID AGREEMENT_ID = UUID.randomUUID();
  private static final String BASE_URL = "https://app.example.test";

  private static StaffAgreementView view(String templateState) {
    return new StaffAgreementView(
        AGREEMENT_ID,
        "AM7K2P9Q",
        "Hyderabad",
        LocalDate.of(2026, 1, 1),
        "Residential rental agreement",
        templateState,
        List.of(
            new StaffPartyView(Role.OWNER, "Asha Owner", "Ravi Owner"),
            new StaffPartyView(Role.TENANT, "Tara Tenant", "Hari Tenant")));
  }

  @Test
  void theMessageCarriesTheReferenceAndStateAndNothingPersonal() {
    StaffAlertMessage message = StaffAlertMessages.from(view("TG"), BASE_URL);

    assertThat(message.trackingReference()).isEqualTo("AM7K2P9Q");
    assertThat(message.stateCode()).isEqualTo("TG");
    assertThat(message.siteLink()).isEqualTo(URI.create(BASE_URL));

    String rendered = message + " " + DiscordStaffNotifier.body(message);
    assertThat(rendered).contains("AM7K2P9Q").contains("TG").contains(BASE_URL);
    assertThat(rendered)
        .doesNotContain("Asha")
        .doesNotContain("Ravi")
        .doesNotContain("Tara")
        .doesNotContain("Hari")
        .doesNotContain("Hyderabad")
        .doesNotContain(AGREEMENT_ID.toString());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"Telangana", "tg", "T", "TGX", "T1"})
  void aStateThatIsNotTwoCapitalLettersIsOmitted(String templateState) {
    StaffAlertMessage message = StaffAlertMessages.from(view(templateState), BASE_URL);

    assertThat(message.stateCode()).isNull();
    assertThat(DiscordStaffNotifier.content(message))
        .contains("AM7K2P9Q")
        .doesNotContain("(")
        .doesNotContainIgnoringCase("telangana");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {"", "   ", "http://localhost:5173", "app.example.test", "/start", "ht tp://x"})
  void aSiteAddressThatIsNotSecureAndAbsoluteIsOmitted(String publicBaseUrl) {
    StaffAlertMessage message = StaffAlertMessages.from(view("TG"), publicBaseUrl);

    assertThat(message.siteLink()).isNull();
    assertThat(DiscordStaffNotifier.content(message)).doesNotContain("http").doesNotContain("\n");
  }

  @Test
  void anHttpsSiteAddressIsLinkedTrimmedAndOtherwiseAsConfigured() {
    assertThat(StaffAlertMessages.from(view("TG"), " https://app.example.test/ ").siteLink())
        .isEqualTo(URI.create("https://app.example.test/"));
  }
}

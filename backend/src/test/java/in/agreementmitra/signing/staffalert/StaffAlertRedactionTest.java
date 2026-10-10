package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.AgreementIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Nothing in this package prints the webhook URL or a full agreement id through toString(). */
class StaffAlertRedactionTest {

  private static final String URL = "https://hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d";

  @Test
  void thePropertiesMaskTheWebhookUrl() {
    StaffAlertProperties.Discord discord = new StaffAlertProperties.Discord(URL);
    StaffAlertProperties properties = new StaffAlertProperties(discord);

    assertThat(discord.webhookUrl()).isEqualTo(URL);
    assertThat(discord.toString()).doesNotContain("hooks.example.test").doesNotContain("tok-");
    assertThat(properties.toString()).doesNotContain("hooks.example.test").doesNotContain("tok-");
  }

  @Test
  void unsetPropertiesBindToABlankUrl() {
    assertThat(new StaffAlertProperties(null).discord().webhookUrl()).isEmpty();
    assertThat(new StaffAlertProperties.Discord(null).webhookUrl()).isEmpty();
  }

  @Test
  void theAlertRowPrintsOnlyARedactedAgreementId() {
    UUID agreementId = UUID.randomUUID();
    StaffAlert alert = new StaffAlert();
    ReflectionTestUtils.setField(alert, "agreementId", agreementId);

    assertThat(alert.toString())
        .contains(AgreementIds.redact(agreementId))
        .doesNotContain(agreementId.toString());
  }

  @Test
  void theDeliveryExceptionHasNoWayToCarryACause() {
    assertThat(StaffAlertDeliveryException.class.getDeclaredConstructors())
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterTypes()).doesNotContain(Throwable.class));
  }
}

package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.SurplusPayment;
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

  /** For a paid-order alert the key IS the agreement id, so the key is never printed either. */
  @Test
  void theAlertRowPrintsItsKindAndOnlyARedactedAgreementId() {
    UUID agreementId = UUID.randomUUID();
    UUID paymentOrderId = UUID.randomUUID();
    StaffAlert paid = new StaffAlert();
    ReflectionTestUtils.setField(paid, "id", agreementId);
    ReflectionTestUtils.setField(paid, "kind", StaffAlertKind.ORDER_PAID);
    ReflectionTestUtils.setField(paid, "agreementId", agreementId);
    StaffAlert duplicate = new StaffAlert();
    ReflectionTestUtils.setField(duplicate, "id", paymentOrderId);
    ReflectionTestUtils.setField(duplicate, "kind", StaffAlertKind.DUPLICATE_PAYMENT);
    ReflectionTestUtils.setField(duplicate, "agreementId", agreementId);

    assertThat(paid.toString())
        .contains("ORDER_PAID")
        .contains(AgreementIds.redact(agreementId))
        .doesNotContain(agreementId.toString());
    assertThat(duplicate.toString())
        .contains("DUPLICATE_PAYMENT")
        .contains(AgreementIds.redact(agreementId))
        .doesNotContain(agreementId.toString())
        .doesNotContain(paymentOrderId.toString());
  }

  @Test
  void aSurplusPaymentPrintsNoFullIdentifier() {
    UUID paymentOrderId = UUID.randomUUID();
    UUID agreementId = UUID.randomUUID();

    assertThat(new SurplusPayment(paymentOrderId, agreementId).toString())
        .contains(AgreementIds.redact(agreementId))
        .doesNotContain(agreementId.toString())
        .doesNotContain(paymentOrderId.toString());
  }

  @Test
  void theDeliveryExceptionHasNoWayToCarryACause() {
    assertThat(StaffAlertDeliveryException.class.getDeclaredConstructors())
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterTypes()).doesNotContain(Throwable.class));
  }
}

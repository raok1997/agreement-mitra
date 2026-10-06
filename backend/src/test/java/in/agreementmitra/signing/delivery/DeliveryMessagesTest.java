package in.agreementmitra.signing.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.OperatingEntity;
import org.junit.jupiter.api.Test;

class DeliveryMessagesTest {

  private static final String FIRM_GSTIN = "27AAPFU0939F1ZV";

  @Test
  void attachmentMessageEndsWithTheOperatorLineAndNoLlpinWhenNoneIsIssued() {
    String line = DeliveryMessages.operatorLine(new OperatingEntity(null, null));

    String body =
        DeliveryMessages.withAttachment("a@example.test", "AM-1", new byte[] {1}, line).body();

    assertThat(body).endsWith("\n\nAgreementMitra is a service of KAVISAT TEK LABS LLP.\n");
    assertThat(body).doesNotContain("LLPIN");
  }

  @Test
  void notificationMessageEndsWithTheOperatorLineAndItsLlpin() {
    String line = DeliveryMessages.operatorLine(new OperatingEntity("ACA-1234", null));

    String body = DeliveryMessages.notificationOnly("a@example.test", "AM-1", line).body();

    assertThat(body)
        .endsWith("\n\nAgreementMitra is a service of KAVISAT TEK LABS LLP (LLPIN ACA-1234).\n");
  }

  @Test
  void theGstinIsNeverInTheLineEvenWhenConfigured() {
    String line = DeliveryMessages.operatorLine(new OperatingEntity("ACA-1234", FIRM_GSTIN));

    assertThat(line).doesNotContain(FIRM_GSTIN).doesNotContain("GST");
  }
}

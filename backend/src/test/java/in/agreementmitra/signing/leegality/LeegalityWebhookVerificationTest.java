package in.agreementmitra.signing.leegality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for Leegality webhook verification — pure HMAC logic, no Spring, no network. The MAC
 * is {@code HMAC-SHA1(documentId, webhookSecret)} carried in the JSON body.
 */
class LeegalityWebhookVerificationTest {

  private static final String MAC_KEY = "unit-test-mac-key";
  private static final String DOC = "DOC-12345";

  private final LeegalityEsignProvider adapter =
      new LeegalityEsignProvider(
          RestClient.create(), // never called by verifyWebhook
          new LeegalityProperties("", "", MAC_KEY, "profile"),
          new ObjectMapper());

  @Test
  void validMacReturnsDocumentId() {
    String mac = LeegalityEsignProvider.hmacSha1Hex(DOC, MAC_KEY);
    String payload =
        "{\"documentId\":\"" + DOC + "\",\"mac\":\"" + mac + "\",\"event\":\"SIGNED\"}";

    assertThat(adapter.verifyWebhook(payload)).contains(DOC);
  }

  @Test
  void tamperedMacIsRejected() {
    String mac = LeegalityEsignProvider.hmacSha1Hex(DOC, MAC_KEY);
    String tampered = mac.substring(0, mac.length() - 1) + (mac.endsWith("0") ? "1" : "0");
    String payload = "{\"documentId\":\"" + DOC + "\",\"mac\":\"" + tampered + "\"}";

    assertThat(adapter.verifyWebhook(payload)).isEmpty();
  }

  @Test
  void macComputedForADifferentDocumentIsRejected() {
    // A valid MAC, but for another document id — must not validate against this payload's id.
    String macForOther = LeegalityEsignProvider.hmacSha1Hex("OTHER-DOC", MAC_KEY);
    String payload = "{\"documentId\":\"" + DOC + "\",\"mac\":\"" + macForOther + "\"}";

    assertThat(adapter.verifyWebhook(payload)).isEmpty();
  }

  @Test
  void missingFieldsAreRejected() {
    assertThat(adapter.verifyWebhook("{\"documentId\":\"" + DOC + "\"}")).isEmpty();
    assertThat(adapter.verifyWebhook("{\"mac\":\"abc\"}")).isEmpty();
    assertThat(adapter.verifyWebhook("not json")).isEmpty();
  }

  @Test
  void hmacIsStableAndKeyDependent() {
    assertThat(LeegalityEsignProvider.hmacSha1Hex(DOC, MAC_KEY))
        .isEqualTo(LeegalityEsignProvider.hmacSha1Hex(DOC, MAC_KEY))
        .isNotEqualTo(LeegalityEsignProvider.hmacSha1Hex(DOC, "another-mac-key"));
  }

  @Test
  void verifyWebhookReturnsOptional() {
    Optional<String> result = adapter.verifyWebhook("{}");
    assertThat(result).isEmpty();
  }
}

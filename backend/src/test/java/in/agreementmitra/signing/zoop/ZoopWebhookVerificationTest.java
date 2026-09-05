package in.agreementmitra.signing.zoop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.signing.WebhookHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for ZOOP webhook verification - no Spring, no network.
 *
 * <p>ZOOP does not sign its callback. It presents a {@code webhook-security-key} HTTP header whose
 * value is the per-transaction key that transaction's {@code /v5/init} returned. That means the
 * credential proves only <b>possession of the key</b> and binds nothing to the payload - which is
 * precisely why the module never trusts the body and always re-reads authoritative state.
 *
 * <p>These tests pin the four ways verification must fail closed: no header, a wrong key, a key
 * that is genuine but belongs to a <em>different</em> transaction, and an unknown transaction
 * (which yields no stored key at all, and must be indistinguishable from a wrong key).
 */
class ZoopWebhookVerificationTest {

  private static final String GROUP = "GRP-ABC-123";
  private static final String KEY = "3f7c1c9a-77bd-4a1f-9d2e-2c5b8a6e4d10";
  private static final String OTHER_TRANSACTIONS_KEY = "9a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d";

  private final ZoopEsignProvider adapter =
      new ZoopEsignProvider(
          RestClient.create(), // never called by webhook verification
          new ZoopProperties(
              "https://test.zoop.plus/contract/esign/",
              "app",
              "key",
              10080,
              List.of("test.zoop.plus"),
              "https://hooks.example.com",
              "https://app.example.com",
              "AgreementMitra"),
          new ObjectMapper());

  private static String body(String groupId) {
    return "{\"group_id\":\""
        + groupId
        + "\",\"request_id\":\"REQ-1\",\"success\":true,\"response_code\":\"200\"}";
  }

  private static WebhookHeaders headers(String keyValue) {
    return WebhookHeaders.of(Map.of(ZoopEsignProvider.WEBHOOK_KEY_HEADER, keyValue));
  }

  // --- parse (step one of parse-then-verify) ---------------------------------

  @Test
  void theTransactionIdIsParsedFromTheUntrustedBody() {
    assertThat(adapter.parseWebhookTransactionId(body(GROUP))).contains(GROUP);
  }

  @Test
  void anUnparseableBodyNamesNoTransaction() {
    assertThat(adapter.parseWebhookTransactionId("not json")).isEmpty();
    assertThat(adapter.parseWebhookTransactionId("{}")).isEmpty();
    assertThat(adapter.parseWebhookTransactionId("{\"group_id\":\"\"}")).isEmpty();
  }

  // --- verify (step two) -----------------------------------------------------

  @Test
  void theCorrectPerTransactionKeyPasses() {
    assertThat(adapter.verifyWebhook(body(GROUP), headers(KEY), KEY)).contains(GROUP);
  }

  @Test
  void aWrongKeyIsRejected() {
    assertThat(adapter.verifyWebhook(body(GROUP), headers("wrong-key"), KEY)).isEmpty();
  }

  @Test
  void aKeyValidForADifferentTransactionIsRejected() {
    // The attacker holds a GENUINE key - just not this transaction's. Because the module loads the
    // key by the group id the body names, presenting another transaction's key cannot work.
    assertThat(adapter.verifyWebhook(body(GROUP), headers(OTHER_TRANSACTIONS_KEY), KEY)).isEmpty();
  }

  @Test
  void aMissingHeaderIsRejected() {
    assertThat(adapter.verifyWebhook(body(GROUP), WebhookHeaders.empty(), KEY)).isEmpty();
    assertThat(adapter.verifyWebhook(body(GROUP), null, KEY)).isEmpty();
  }

  @Test
  void anUnknownTransactionIsRejectedIndistinguishablyFromAWrongKey() {
    // No stored key (the module found no such transaction) -> the same empty result as a mismatch.
    // Nothing in the outcome tells the caller which of the two it was: no existence oracle.
    assertThat(adapter.verifyWebhook(body("GRP-UNKNOWN"), headers(KEY), null)).isEmpty();
  }

  @Test
  void aBodyThatNamesNoTransactionIsRejectedEvenWithAValidKey() {
    assertThat(adapter.verifyWebhook("{}", headers(KEY), KEY)).isEmpty();
  }

  @Test
  void theHeaderNameIsMatchedCaseInsensitively() {
    // HTTP header names are case-insensitive and containers differ on normalisation; a verification
    // that silently depended on the vendor's exact casing would fail closed in production only.
    assertThat(
            adapter.verifyWebhook(
                body(GROUP), WebhookHeaders.of(Map.of("Webhook-Security-Key", KEY)), KEY))
        .contains(GROUP);
  }

  // --- constant-time comparison ---------------------------------------------

  @Test
  void comparisonIsConstantTimeOverHashesSoNeitherContentNorLengthLeaks() {
    assertThat(ZoopEsignProvider.constantTimeEquals(KEY, KEY)).isTrue();
    // A shared prefix must not shortcut...
    assertThat(ZoopEsignProvider.constantTimeEquals(KEY, KEY.substring(0, KEY.length() - 1) + "X"))
        .isFalse();
    // ...and neither must a length difference. Both sides are hashed to a fixed width first, so
    // MessageDigest.isEqual never gets to short-circuit on length - which for a directly-presented
    // vendor key (unlike a fixed-width MAC digest) would leak the credential's length.
    assertThat(ZoopEsignProvider.constantTimeEquals(KEY, KEY + "extra")).isFalse();
    assertThat(ZoopEsignProvider.constantTimeEquals("", KEY)).isFalse();
  }

  @Test
  void redactionKeepsOnlyATrailingFragment() {
    assertThat(ZoopEsignProvider.redact("GRP-ABC-123")).isEqualTo("****-123");
    assertThat(ZoopEsignProvider.redact("abc")).isEqualTo("****");
    assertThat(ZoopEsignProvider.redact(null)).isEqualTo("****");
  }

  @Test
  void headerValuesAreNeverRenderedByToString() {
    assertThat(headers(KEY).toString()).doesNotContain(KEY);
  }
}

package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two Razorpay signatures, and the ways they are commonly got wrong.
 *
 * <p>The point of most of these cases is not that a correct signature passes - that is easy - but
 * that the near-misses fail: the wrong key, a body altered after signing, a missing header, and
 * above all a body that has been through a JSON round-trip.
 */
class RazorpaySignaturesTest {

  /** Fabricated. No live account exists for this repository and none is needed. */
  private static final String WEBHOOK_KEY = "wh-mac-1";

  private static final String API_KEY = "rzp-key-1";

  private static final String ORDER_ID = "order_TESTabc123";
  private static final String PAYMENT_ID = "pay_TESTxyz789";

  // --- webhook: raw body under the webhook key -------------------------------

  @Test
  void aValidlySignedWebhookIsAccepted() {
    String body = "{\"event\":\"payment.captured\"}";
    String signature = RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_KEY);

    assertThat(RazorpaySignatures.webhookSignatureValid(body, signature, WEBHOOK_KEY)).isTrue();
  }

  @Test
  void aBodyAlteredAfterSigningIsRejected() {
    String body = "{\"event\":\"payment.captured\",\"amount\":49900}";
    String signature = RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_KEY);
    String tampered = body.replace("49900", "1");

    assertThat(RazorpaySignatures.webhookSignatureValid(tampered, signature, WEBHOOK_KEY))
        .isFalse();
  }

  @Test
  void aMissingOrForgedSignatureIsRejected() {
    String body = "{\"event\":\"order.paid\"}";

    assertThat(RazorpaySignatures.webhookSignatureValid(body, null, WEBHOOK_KEY)).isFalse();
    assertThat(RazorpaySignatures.webhookSignatureValid(body, "  ", WEBHOOK_KEY)).isFalse();
    assertThat(RazorpaySignatures.webhookSignatureValid(body, "deadbeef", WEBHOOK_KEY)).isFalse();
  }

  @Test
  void theApiKeyDoesNotValidateAWebhook() {
    // The classic crossed-wires bug. Signing a webhook with the API key must NOT verify: the
    // webhook key is a different value keying a different channel, and if the two were
    // interchangeable a leaked API credential would let anyone forge a payment confirmation.
    String body = "{\"event\":\"payment.captured\"}";
    String signedWithTheWrongKey = RazorpaySignatures.hmacSha256Hex(body, API_KEY);

    assertThat(RazorpaySignatures.webhookSignatureValid(body, signedWithTheWrongKey, WEBHOOK_KEY))
        .isFalse();
  }

  @Test
  void anUnconfiguredWebhookKeyVerifiesNothing() {
    // Fail closed: a deployment with no webhook credential rejects every webhook rather than
    // accepting any. Treating "nothing configured" as "no verification needed" is how forged
    // payment confirmations get in.
    String body = "{\"event\":\"payment.captured\"}";

    assertThat(RazorpaySignatures.webhookSignatureValid(body, "anything", null)).isFalse();
    assertThat(RazorpaySignatures.webhookSignatureValid(body, "anything", "")).isFalse();
  }

  @Test
  void verificationUsesTheRawBytesAndNotAReSerialisedForm() {
    // A body whose formatting would NOT survive a JSON round-trip: keys out of alphabetical order,
    // irregular whitespace, and a trailing newline. Jackson would re-emit this differently, so a
    // verifier that parsed first and re-serialised would fail on every legitimate webhook. This is
    // the single most common cause of "signature mismatch", and it fails 100% of the time.
    String rawBody =
        "{\n  \"payload\" : {\"payment\":{\"entity\":{\"amount\":49900,   \"id\":\"pay_1\"}}},\n"
            + "\t\"event\":\"payment.captured\",  \"created_at\" : 1700000000\n}\n";
    String signature = RazorpaySignatures.hmacSha256Hex(rawBody, WEBHOOK_KEY);

    assertThat(RazorpaySignatures.webhookSignatureValid(rawBody, signature, WEBHOOK_KEY)).isTrue();

    // Prove the round-trip really would have broken it: any reformatting changes the digest.
    String reformatted =
        "{\"created_at\":1700000000,\"event\":\"payment.captured\","
            + "\"payload\":{\"payment\":{\"entity\":{\"amount\":49900,\"id\":\"pay_1\"}}}}";
    assertThat(RazorpaySignatures.webhookSignatureValid(reformatted, signature, WEBHOOK_KEY))
        .isFalse();
  }

  // --- checkout handler: order_id|payment_id under the API key ---------------

  @Test
  void theHandlerSignatureIsComputedOverOrderIdPipePaymentId() {
    String expected = RazorpaySignatures.hmacSha256Hex(ORDER_ID + "|" + PAYMENT_ID, API_KEY);

    assertThat(RazorpaySignatures.handlerSignatureValid(ORDER_ID, PAYMENT_ID, expected, API_KEY))
        .isTrue();
    // Order matters: payment_id|order_id is a different message and must not verify.
    String reversed = RazorpaySignatures.hmacSha256Hex(PAYMENT_ID + "|" + ORDER_ID, API_KEY);
    assertThat(RazorpaySignatures.handlerSignatureValid(ORDER_ID, PAYMENT_ID, reversed, API_KEY))
        .isFalse();
  }

  @Test
  void theWebhookKeyDoesNotValidateAHandlerSignature() {
    // The mirror image of the crossed-wires bug, and just as important: two channels, two keys, and
    // neither may stand in for the other.
    String signedWithTheWrongKey =
        RazorpaySignatures.hmacSha256Hex(ORDER_ID + "|" + PAYMENT_ID, WEBHOOK_KEY);

    assertThat(
            RazorpaySignatures.handlerSignatureValid(
                ORDER_ID, PAYMENT_ID, signedWithTheWrongKey, API_KEY))
        .isFalse();
  }

  @Test
  void anIncompleteHandlerCallbackIsRejected() {
    String signature = RazorpaySignatures.hmacSha256Hex(ORDER_ID + "|" + PAYMENT_ID, API_KEY);

    assertThat(RazorpaySignatures.handlerSignatureValid(null, PAYMENT_ID, signature, API_KEY))
        .isFalse();
    assertThat(RazorpaySignatures.handlerSignatureValid(ORDER_ID, null, signature, API_KEY))
        .isFalse();
    assertThat(RazorpaySignatures.handlerSignatureValid(ORDER_ID, PAYMENT_ID, null, API_KEY))
        .isFalse();
    assertThat(RazorpaySignatures.handlerSignatureValid(ORDER_ID, PAYMENT_ID, signature, ""))
        .isFalse();
  }

  // --- comparison ------------------------------------------------------------

  @Test
  void comparisonIsConstantTimeAndDoesNotLeakLength() {
    // Both sides are hashed before comparison, so fixed-width digests are what MessageDigest sees.
    // That matters because isEqual short-circuits on a LENGTH mismatch: comparing the raw strings
    // would let an attacker who can time us learn how long a live signature is.
    assertThat(RazorpaySignatures.constantTimeEquals("abc", "abc")).isTrue();
    assertThat(RazorpaySignatures.constantTimeEquals("abc", "abd")).isFalse();
    assertThat(RazorpaySignatures.constantTimeEquals("a", "aaaaaaaaaaaaaaaaaaaaaaaa")).isFalse();
    assertThat(RazorpaySignatures.constantTimeEquals("", "")).isTrue();
  }

  @Test
  void theDigestIsLowercaseHexSha256Width() {
    String digest = RazorpaySignatures.hmacSha256Hex("payload", WEBHOOK_KEY);

    assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
  }
}

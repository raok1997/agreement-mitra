package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pins the <b>shape</b> of the progress view, without Spring. The integration test proves a real
 * response leaks no signing URL or PII; this one proves nobody can add a field that would, by
 * enumerating the record components and refusing any whose name smells like a capability, a
 * credential, or an explanation the customer is not meant to see.
 */
class SigningProgressResponseShapeTest {

  private static final List<String> DENYLIST =
      List.of(
          "url",
          "otp",
          "aadhaar",
          "vid",
          "token",
          "secret",
          "credential",
          "reason",
          "certificate",
          "email",
          "mobile",
          "name",
          "address",
          "key");

  @Test
  void theResponseHasExactlyTheAgreedComponents() {
    assertThat(componentNames(SigningProgressResponse.class))
        .containsExactly(
            "agreementId", "status", "stage", "terminal", "signedDocumentReady", "parties");
    assertThat(componentNames(SigningProgressResponse.PartyProgress.class))
        .containsExactly("signerId", "role", "status");
  }

  @Test
  void noComponentNameSmellsLikeACapabilityCredentialOrExplanation() {
    for (Class<?> type :
        List.of(SigningProgressResponse.class, SigningProgressResponse.PartyProgress.class)) {
      for (String name : componentNames(type)) {
        String lower = name.toLowerCase(Locale.ROOT);
        assertThat(DENYLIST).as("%s.%s", type.getSimpleName(), name).noneMatch(lower::contains);
      }
    }
  }

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}

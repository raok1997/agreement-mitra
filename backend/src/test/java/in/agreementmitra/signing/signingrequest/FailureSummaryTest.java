package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class FailureSummaryTest {

  @Test
  void namesTheTypeAndOurThrowSiteButNeverTheMessage() {
    String summary = FailureSummary.of(new IllegalStateException("secret-id-123"));

    assertThat(summary)
        .startsWith("IllegalStateException at FailureSummaryTest.")
        .doesNotContain("secret-id-123");
  }

  @Test
  void addsTheHttpStatusOfAProviderErrorWithoutItsBody() {
    HttpClientErrorException notFound =
        HttpClientErrorException.create(
            HttpStatus.NOT_FOUND,
            "Not Found",
            null,
            "signer-body".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

    String summary = FailureSummary.of(notFound);

    assertThat(summary).contains("HTTP 404").doesNotContain("signer-body");
  }

  @Test
  void omitsTheThrowSiteWhenNoFrameIsOurs() {
    RuntimeException foreign = new RuntimeException("x");
    foreign.setStackTrace(
        new StackTraceElement[] {new StackTraceElement("org.example.Lib", "call", "Lib.java", 7)});

    assertThat(FailureSummary.of(foreign)).isEqualTo("RuntimeException");
  }
}

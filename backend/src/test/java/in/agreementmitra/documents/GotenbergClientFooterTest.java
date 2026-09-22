package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GotenbergClient#footerHtml(String)} -- the per-page PDF footer furniture:
 * the escaped reference + platform URL on the left, and Chromium's page-number placeholders on the
 * right. The furniture lives in the reserved bottom margin (not the body flow), so it never orphans
 * onto its own page. No Gotenberg round-trip: the footer template is built in-process, so this
 * asserts its shape directly (the {@link org.springframework.web.client.RestClient} the client
 * would use is unused here and passed as {@code null}).
 */
class GotenbergClientFooterTest {

  private static final GotenbergProperties GOTENBERG =
      new GotenbergProperties("http://localhost:3000", 4, Duration.ofSeconds(30));

  private static GotenbergClient clientWithFooterUrl(String platformUrl) {
    return new GotenbergClient(null, GOTENBERG, new DocumentFooterProperties(platformUrl, ""));
  }

  @Test
  void footerCarriesTheReferencePlatformUrlAndPageNumberPlaceholders() {
    String footer = clientWithFooterUrl("agreementmitra.com").footerHtml("AM-A5E4D7-010726");

    assertThat(footer)
        .contains("AM-A5E4D7-010726") // the reference (tracking number or marker)
        .contains("agreementmitra.com") // the configured platform URL
        .contains("class=\"pageNumber\"") // Chromium page-number placeholders
        .contains("class=\"totalPages\"");
    // The count is of the AGREEMENT, not of the delivered file: the e-stamp certificate is bound in
    // front as page 1 after this footer is stamped, so a bare "Page X of Y" understated the file by
    // one page on every instrument.
    assertThat(footer).contains("Agreement page");
  }

  @Test
  void footerRendersTheReferenceAloneWhenThePlatformUrlIsBlank() {
    String footer = clientWithFooterUrl("").footerHtml("AM-A5E4D7-010726");

    assertThat(footer)
        .contains("AM-A5E4D7-010726")
        .contains("class=\"pageNumber\"")
        .doesNotContain("agreementmitra.com");
  }

  @Test
  void footerEscapesAHostileReferenceAndUrlSoNeitherCanInjectMarkup() {
    String footer =
        clientWithFooterUrl("<b>evil.example</b>").footerHtml("<script>alert('x')</script>");

    assertThat(footer)
        .contains("&lt;script&gt;")
        .contains("&lt;b&gt;")
        .doesNotContain("<script>alert")
        .doesNotContain("<b>evil.example</b>");
  }
}

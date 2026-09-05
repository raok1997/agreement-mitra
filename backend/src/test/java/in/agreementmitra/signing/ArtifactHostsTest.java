package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared artifact-URL SSRF guard. A signed-document URL arrives inside a vendor
 * response, so it is untrusted input as far as our process is concerned: it decides what host our
 * server makes an outbound request to.
 */
class ArtifactHostsTest {

  private static final List<String> ALLOWED = List.of("test.zoop.plus", "esign.zoop.plus");

  @Test
  void aListedHostIsAccepted() {
    assertThatCode(() -> ArtifactHosts.require("https://esign.zoop.plus/f/1.pdf", ALLOWED))
        .doesNotThrowAnyException();
  }

  @Test
  void aSecondListedHostIsAcceptedSoExpiringLinksOnAnotherHostStillWork() {
    // The reason this is an allowlist and not a single pin: ZOOP's signed-document links live on a
    // different host from its API.
    assertThatCode(() -> ArtifactHosts.require("https://test.zoop.plus/x", ALLOWED))
        .doesNotThrowAnyException();
  }

  @Test
  void hostMatchingIsCaseInsensitive() {
    assertThatCode(() -> ArtifactHosts.require("https://ESIGN.ZOOP.PLUS/f/1.pdf", ALLOWED))
        .doesNotThrowAnyException();
  }

  @Test
  void anUnlistedHostIsRefused() {
    assertThatThrownBy(() -> ArtifactHosts.require("https://evil.example.com/steal", ALLOWED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider host");
  }

  @Test
  void aLookalikeSubdomainIsRefused() {
    // "esign.zoop.plus.evil.com" must not pass because it ends with an allowed label sequence.
    assertThatThrownBy(
            () -> ArtifactHosts.require("https://esign.zoop.plus.evil.com/steal", ALLOWED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anEmptyAllowlistRefusesEverythingRatherThanPermittingIt() {
    // Fail closed: a misconfiguration must not silently open an arbitrary outbound fetch.
    assertThatThrownBy(() -> ArtifactHosts.require("https://test.zoop.plus/x", List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ArtifactHosts.require("https://test.zoop.plus/x", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aHostlessOrUnparseableUrlIsRefused() {
    assertThatThrownBy(() -> ArtifactHosts.require("file:///etc/passwd", ALLOWED))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ArtifactHosts.require("not a url", ALLOWED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theRefusalNeverEchoesTheUrlBecauseItMayBeABearerCapability() {
    assertThatThrownBy(
            () -> ArtifactHosts.require("https://evil.example.com/token/SECRETVALUE", ALLOWED))
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRETVALUE"));
  }
}

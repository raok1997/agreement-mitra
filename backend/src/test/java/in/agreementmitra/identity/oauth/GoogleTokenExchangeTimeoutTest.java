package in.agreementmitra.identity.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.identity.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A hung Google token endpoint is cut off by the read timeout and surfaces as the ordinary login
 * refusal, instead of holding the callback's transaction open (GoogleHttp). Runs with a short
 * timeout against a WireMock that answers late; the production values are pinned separately.
 */
class GoogleTokenExchangeTimeoutTest {

  private final WireMockServer google = new WireMockServer(options().dynamicPort());

  @BeforeEach
  void startGoogle() {
    google.start();
  }

  @AfterEach
  void stopGoogle() {
    google.stop();
  }

  private GoogleTokenExchange exchange(Duration readTimeout) {
    AuthProperties props =
        new AuthProperties(
            "pepper",
            Duration.ofHours(1),
            Duration.ofSeconds(60),
            Duration.ofMinutes(5),
            new AuthProperties.Google(
                "client",
                "secret",
                "redirect",
                "spa",
                "issuer",
                "auth",
                google.baseUrl() + "/token",
                "jwks"),
            null);
    return new GoogleTokenExchange(
        props, GoogleHttp.requestFactory(Duration.ofSeconds(1), readTimeout));
  }

  @Test
  void aSlowTokenEndpointIsRefusedAtTheReadTimeout() {
    google.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"id_token\":\"late\"}").withFixedDelay(2_000)));

    long started = System.nanoTime();
    assertThatThrownBy(() -> exchange(Duration.ofMillis(200)).exchangeForIdToken("code", "v"))
        .isInstanceOf(InvalidLoginException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void aPromptTokenEndpointStillAnswers() {
    google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("{\"id_token\":\"tok\"}")));

    assertThat(exchange(Duration.ofSeconds(2)).exchangeForIdToken("code", "v")).isEqualTo("tok");
  }

  @Test
  void theProductionTimeoutsStayWellUnderTheLoginBindingMargin() {
    assertThat(
            GoogleHttp.CONNECT_TIMEOUT
                .plus(GoogleHttp.READ_TIMEOUT)
                .multipliedBy(GoogleHttp.MAX_CALLS_PER_CALLBACK))
        .isLessThan(Duration.ofSeconds(60));
  }
}

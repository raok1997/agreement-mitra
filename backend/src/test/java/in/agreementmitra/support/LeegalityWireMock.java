package in.agreementmitra.support;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A WireMock stand-in for the Leegality sandbox. The test profile pins the {@code leegality}
 * provider with a blank base URL and there is no stub {@code EsignProvider} bean, so a test that
 * initiates eSign starts one of these, points the adapter at it from its own
 * {@code @DynamicPropertySource}, and stubs the create call.
 */
public final class LeegalityWireMock {

  public static final String AUTH_TOKEN = "it-auth-token";
  public static final String WEBHOOK_MAC_KEY = "it-mac-key-value";

  private LeegalityWireMock() {}

  /** A started server on a dynamic port; stop it in an {@code @AfterAll}. */
  public static WireMockServer start() {
    WireMockServer server = new WireMockServer(options().dynamicPort());
    server.start();
    return server;
  }

  /** Point the Leegality adapter at {@code server}. Call from a {@code @DynamicPropertySource}. */
  public static void register(DynamicPropertyRegistry registry, WireMockServer server) {
    registry.add("esign.leegality.base-url", () -> server.baseUrl() + "/api/");
    registry.add("esign.leegality.auth-token", () -> AUTH_TOKEN);
    registry.add("esign.leegality.webhook-secret", () -> WEBHOOK_MAC_KEY);
    registry.add("esign.leegality.profile-id", () -> "it-profile");
  }

  /** A successful two-invitee sign-request create returning {@code documentId}. */
  public static void stubCreate(WireMockServer server, String documentId) {
    server.stubFor(
        post(urlEqualTo("/api/v3.0/sign/request"))
            .willReturn(
                okJson(
                    "{\"status\":\"SUCCESS\",\"data\":{\"documentId\":\""
                        + documentId
                        + "\",\"invitees\":[{\"signUrl\":\"https://sign/1\",\"expiryDate\":\"2026-01-01\"},"
                        + "{\"signUrl\":\"https://sign/2\",\"expiryDate\":\"2026-01-02\"}]}}")));
  }
}

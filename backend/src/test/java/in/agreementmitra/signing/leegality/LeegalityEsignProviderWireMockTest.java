package in.agreementmitra.signing.leegality;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Integration test for the Leegality adapter against a stubbed Leegality (WireMock) — exercises the
 * real HTTP calls, headers, request body shape, and response mapping for create + details. No
 * Spring context, no Docker; runs everywhere.
 */
class LeegalityEsignProviderWireMockTest {

  private static final String AUTH_TOKEN = "stub-auth-token";
  private static final String PROFILE_ID = "profile-1";

  private WireMockServer server;
  private LeegalityEsignProvider adapter;

  @BeforeEach
  void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
    String base = server.baseUrl() + "/api/";
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    RestClient client =
        RestClient.builder()
            .baseUrl(base)
            .defaultHeader("X-Auth-Token", AUTH_TOKEN)
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .build();
    adapter =
        new LeegalityEsignProvider(
            client,
            new LeegalityProperties(base, AUTH_TOKEN, "mac", PROFILE_ID),
            new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void createSignRequestPostsExpectedBodyAndMapsResponse() {
    server.stubFor(
        post(urlEqualTo("/api/v3.0/sign/request"))
            .withHeader("X-Auth-Token", equalTo(AUTH_TOKEN))
            .withRequestBody(matchingJsonPath("$.profileId", equalTo(PROFILE_ID)))
            .withRequestBody(matchingJsonPath("$.file.file")) // base64 PDF present
            .withRequestBody(matchingJsonPath("$.invitees[0].aadhaarConfig.verifyName"))
            .willReturn(
                okJson(
                    """
                    {"status":"SUCCESS","data":{"documentId":"DOC-1","invitees":[
                      {"inviteeId":"INV-1","signUrl":"https://sign/1","expiryDate":"2026-01-01"},
                      {"inviteeId":"INV-2","signUrl":"https://sign/2","expiryDate":"2026-01-02"}]}}
                    """)));

    SignRequest request =
        new SignRequest(
            "agr-1",
            "%PDF-1.4".getBytes(StandardCharsets.UTF_8),
            List.of(
                new SignRequest.Invitee("Asha", "asha@example.com", null, true),
                new SignRequest.Invitee("Tara", "tara@example.com", "9999999999", true)));

    SignSession session = adapter.createSignRequest(request);

    assertThat(session.providerDocumentId()).isEqualTo("DOC-1");
    assertThat(session.invitees()).hasSize(2);
    assertThat(session.invitees().get(0).email()).isEqualTo("asha@example.com");
    assertThat(session.invitees().get(0).signUrl()).isEqualTo("https://sign/1");
    assertThat(session.invitees().get(1).email()).isEqualTo("tara@example.com");
    assertThat(session.invitees().get(1).expiryDate()).isEqualTo("2026-01-02");
    assertThat(session.invitees().get(0).providerInviteeId()).isEqualTo("INV-1");
    assertThat(session.invitees().get(1).providerInviteeId()).isEqualTo("INV-2");
  }

  @Test
  void getStatusReadsAuthoritativePerInviteeStatuses() {
    server.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .withQueryParam("documentId", equalTo("DOC-1"))
            .withHeader("X-Auth-Token", equalTo(AUTH_TOKEN))
            .willReturn(
                okJson(
                    """
                    {"data":{"invitees":[
                      {"inviteeId":"INV-1","status":"SIGNED"},
                      {"inviteeId":"INV-2","status":"SENT"}]}}
                    """)));

    DocumentStatusView view = adapter.getStatus("DOC-1");

    assertThat(view.invitees()).hasSize(2);
    assertThat(view.invitees().get(0).providerInviteeId()).isEqualTo("INV-1");
    assertThat(view.invitees().get(0).ordinal()).isZero();
    assertThat(view.invitees().get(0).status()).isEqualTo(InviteeStatus.SIGNED);
    // "SENT" (in flight / unknown) maps to PENDING.
    assertThat(view.invitees().get(1).status()).isEqualTo(InviteeStatus.PENDING);
  }

  @Test
  void downloadFetchesArtifactsFromProviderHostedUrls() {
    server.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .willReturn(
                okJson(
                    "{\"data\":{\"document\":{\"status\":\"COMPLETED\","
                        + "\"signedUrl\":\""
                        + server.baseUrl()
                        + "/files/signed.pdf\",\"auditTrailUrl\":\""
                        + server.baseUrl()
                        + "/files/audit.bin\"}}}")));
    server.stubFor(
        get(urlPathEqualTo("/files/signed.pdf"))
            .willReturn(aResponse().withBody("PDFBYTES".getBytes(StandardCharsets.UTF_8))));
    server.stubFor(
        get(urlPathEqualTo("/files/audit.bin"))
            .willReturn(aResponse().withBody("AUDIT".getBytes(StandardCharsets.UTF_8))));

    SignedDocument document = adapter.download("DOC-1");

    assertThat(document.providerDocumentId()).isEqualTo("DOC-1");
    assertThat(new String(document.signedPdf(), StandardCharsets.UTF_8)).isEqualTo("PDFBYTES");
    assertThat(new String(document.auditTrail(), StandardCharsets.UTF_8)).isEqualTo("AUDIT");
  }

  @Test
  void downloadDoesNotLogArtifactBytesOrUrls() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(LeegalityEsignProvider.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String signedUrl = server.baseUrl() + "/files/signed.pdf";
      server.stubFor(
          get(urlPathEqualTo("/api/v3.3/document/details"))
              .willReturn(
                  okJson(
                      "{\"data\":{\"document\":{\"status\":\"COMPLETED\",\"signedUrl\":\""
                          + signedUrl
                          + "\"}}}")));
      server.stubFor(
          get(urlPathEqualTo("/files/signed.pdf"))
              .willReturn(aResponse().withBody("PDFSECRETBYTES".getBytes(StandardCharsets.UTF_8))));

      adapter.download("DOC-SECRET-1234");

      String logged =
          appender.list.stream()
              .map(e -> e.getFormattedMessage())
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(logged).doesNotContain("PDFSECRETBYTES"); // bytes never logged
      assertThat(logged).doesNotContain(signedUrl); // artifact URL never logged
      assertThat(logged).doesNotContain("DOC-SECRET-1234"); // full doc id never logged (redacted)
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void downloadRejectsArtifactUrlOnForeignHost() {
    server.stubFor(
        get(urlPathEqualTo("/api/v3.3/document/details"))
            .willReturn(
                okJson(
                    "{\"data\":{\"document\":{\"status\":\"COMPLETED\","
                        + "\"signedUrl\":\"http://evil.example.com/steal\"}}}")));

    assertThatThrownBy(() -> adapter.download("DOC-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider host");
  }
}

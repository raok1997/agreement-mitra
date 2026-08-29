package in.agreementmitra.signing.zoop;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import in.agreementmitra.support.TestPdfs;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Integration test for the ZOOP eSign v5 adapter against a stubbed ZOOP (WireMock) - the real HTTP
 * calls, auth headers, request body shape, and response mapping for init / fetch-group /
 * audit-trail. No Spring context, no Docker, no live credentials; runs everywhere.
 */
class ZoopEsignProviderWireMockTest {

  private static final String APP_ID = "stub-app-id";
  private static final String API_KEY = "stub-api-key";
  private static final String INIT_URL = "/contract/esign/v5/init";
  private static final String GROUP_URL = "/contract/esign/v5/fetch/group";
  private static final String AUDIT_URL = "/contract/esign/v5/fetch/audit-trail";
  private static final String AGREEMENT_ID = "1a111111-2b22-3c33-4d44-5e5555555555";

  private WireMockServer server;
  private ZoopEsignProvider adapter;

  @BeforeEach
  void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
    adapter = adapterWith(List.of(host()));
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private ZoopEsignProvider adapterWith(List<String> artifactHosts) {
    String base = server.baseUrl() + "/contract/esign/";
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    RestClient client =
        RestClient.builder()
            .baseUrl(base)
            .defaultHeader("app-id", APP_ID)
            .defaultHeader("api-key", API_KEY)
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .build();
    return new ZoopEsignProvider(
        client,
        new ZoopProperties(
            base,
            APP_ID,
            API_KEY,
            10080,
            artifactHosts,
            "https://hooks.example.com/api/webhooks/esign",
            "https://app.example.com/done",
            "AgreementMitra"),
        new ObjectMapper());
  }

  private String host() {
    return URI.create(server.baseUrl()).getHost();
  }

  private static SignRequest twoSignerRequest(byte[] pdf) {
    return new SignRequest(
        AGREEMENT_ID,
        pdf,
        List.of(
            new SignRequest.Invitee("Asha", "asha@example.com", null, true, "esign:owner"),
            new SignRequest.Invitee(
                "Tara", "tara@example.com", "9999999999", true, "esign:tenant")));
  }

  private void stubInit() {
    server.stubFor(
        post(urlEqualTo(INIT_URL))
            .withHeader("app-id", equalTo(APP_ID))
            .withHeader("api-key", equalTo(API_KEY))
            .willReturn(
                okJson(
                    """
                    {"success":true,"group_id":"GRP-1","webhook_security_key":"WHK-1",
                     "expires_at":"2026-08-10T10:00:00Z",
                     "requests":[
                       {"request_id":"REQ-1","signer_name":"Asha","signer_email":"asha@example.com",
                        "signing_order":1,"signing_url":"https://esign.zoop.plus/s/1"},
                       {"request_id":"REQ-2","signer_name":"Tara","signer_email":"tara@example.com",
                        "signing_order":2,"signing_url":"https://esign.zoop.plus/s/2"}]}
                    """)));
  }

  // --- init ------------------------------------------------------------------

  @Test
  void bothSignersGoOutInOneInitCallWithAadhaarSequentialAndProviderSentInvites() {
    stubInit();

    adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));

    server.verify(
        1,
        postRequestedFor(urlEqualTo(INIT_URL))
            // ONE call carrying BOTH signers - never one transaction per signer.
            .withRequestBody(
                matchingJsonPath("$.signers[0].signer_email", equalTo("asha@example.com")))
            .withRequestBody(
                matchingJsonPath("$.signers[1].signer_email", equalTo("tara@example.com")))
            .withRequestBody(matchingJsonPath("$.esign_type", equalTo("AADHAAR")))
            // Sequential: the owner signs before the tenant.
            .withRequestBody(matchingJsonPath("$.signing_type", equalTo("SEQUENTIAL")))
            // ZOOP emails the parties; we send no signing link of our own.
            .withRequestBody(matchingJsonPath("$.send_invite", equalTo("true")))
            // A window measured in DAYS (10080 minutes = 7), not minutes.
            .withRequestBody(matchingJsonPath("$.txn_expiry_min", equalTo("10080")))
            .withRequestBody(matchingJsonPath("$.task_id", equalTo(AGREEMENT_ID)))
            .withRequestBody(matchingJsonPath("$.document.data"))
            .withRequestBody(matchingJsonPath("$.signers[0].signer_purpose")));
  }

  @Test
  void documentInfoMeetsTheVendorsFifteenCharacterMinimumAndCarriesNoPartyData() {
    assertThat(ZoopEsignProvider.DOCUMENT_INFO.length()).isGreaterThanOrEqualTo(15);
    assertThat(ZoopEsignProvider.DOCUMENT_INFO.toLowerCase()).doesNotContain("asha", "tara");
  }

  @Test
  void piiWideningCaptureOptionsAreNeverRequested() {
    stubInit();

    adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));

    String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
    assertThat(body).doesNotContain("location_capture");
    assertThat(body).doesNotContain("photo_capture");
  }

  @Test
  void eachSignerGetsCoordinatesDerivedFromItsOwnAnchorWithXMirrored() {
    stubInit();

    adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));

    String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
    // The owner anchor sits near the LEFT edge, the tenant near the RIGHT. ZOOP measures x from the
    // RIGHT, so the owner's x_coord must be the LARGER of the two. Reading this the other way round
    // is the mirroring bug, and it produces no error anywhere.
    assertThat(jsonInt(body, "x_coord", 0)).isGreaterThan(jsonInt(body, "x_coord", 1));
    assertThat(jsonInt(body, "page_num", 0)).isEqualTo(1);
  }

  private static int jsonInt(String body, String field, int occurrence) {
    Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?[0-9]+)").matcher(body);
    for (int i = 0; i <= occurrence; i++) {
      assertThat(matcher.find()).as("occurrence %d of %s", occurrence, field).isTrue();
    }
    return Integer.parseInt(matcher.group(1));
  }

  @Test
  void initResponseMapsGroupIdPerSignerTokensUrlsAndExpiry() {
    stubInit();

    SignSession session = adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));

    assertThat(session.providerDocumentId()).isEqualTo("GRP-1");
    assertThat(session.invitees()).hasSize(2);
    assertThat(session.invitees().get(0).email()).isEqualTo("asha@example.com");
    assertThat(session.invitees().get(0).providerInviteeId()).isEqualTo("REQ-1");
    assertThat(session.invitees().get(0).signUrl()).isEqualTo("https://esign.zoop.plus/s/1");
    assertThat(session.invitees().get(1).providerInviteeId()).isEqualTo("REQ-2");
    // One expiry for the transaction, carried onto each invitee.
    assertThat(session.invitees().get(0).expiryDate()).isEqualTo("2026-08-10T10:00:00Z");
    // The per-transaction webhook key rides OUT to the module, which encrypts and stores it.
    assertThat(session.webhookKey()).isEqualTo("WHK-1");
  }

  @Test
  void submitsTheBlockPlusAStripOnEveryOtherPageForEachSigner() {
    stubInit();

    // Two pages, anchors on page 2 - the shape stamp intake produces once the certificate scan is
    // prepended.
    adapter.createSignRequest(
        new SignRequest(
            AGREEMENT_ID,
            withBlankPagePrepended(TestPdfs.withEsignAnchors()),
            List.of(
                new SignRequest.Invitee(
                    "Asha",
                    "asha@example.com",
                    null,
                    true,
                    List.of(
                        SignRequest.Placement.anchored("esign:owner"),
                        SignRequest.Placement.everyPageFooter())))));

    // Block on page 2, strip on page 1 -- and NO strip on page 2, which already carries the block:
    // a second signature beside it reads as a mistake on a legal instrument.
    server.verify(
        postRequestedFor(urlEqualTo(INIT_URL))
            .withRequestBody(
                matchingJsonPath("$.signers[0].sign_coordinates[0].page_num", equalTo("2")))
            .withRequestBody(
                matchingJsonPath("$.signers[0].sign_coordinates[1].page_num", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.signers[0].sign_coordinates[2]", absent())));
  }

  @Test
  void aSinglePageInstrumentGetsTheBlockAndNoStripAtAll() {
    stubInit();

    // Nothing to strip: the only page already carries the block.
    adapter.createSignRequest(
        new SignRequest(
            AGREEMENT_ID,
            TestPdfs.withEsignAnchors(),
            List.of(
                new SignRequest.Invitee(
                    "Asha",
                    "asha@example.com",
                    null,
                    true,
                    List.of(
                        SignRequest.Placement.anchored("esign:owner"),
                        SignRequest.Placement.everyPageFooter())))));

    server.verify(
        postRequestedFor(urlEqualTo(INIT_URL))
            .withRequestBody(
                matchingJsonPath("$.signers[0].sign_coordinates[0].page_num", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.signers[0].sign_coordinates[1]", absent())));
  }

  /** The stamped shape: a certificate page prepended, so every anchor page number shifts by one. */
  private static byte[] withBlankPagePrepended(byte[] pdf) {
    try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      document
          .getPages()
          .insertBefore(
              new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4),
              document.getPage(0));
      document.save(out);
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void placementLogsNeitherTheAnchorTokenNorAnyCoordinate() {
    // Placement reads a legal instrument and computes positions on it. Neither the token nor a
    // coordinate may reach the log: a coordinate is tied to a named party's signature zone, and
    // the surrounding lines are the document itself.
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ZoopEsignProvider.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      stubInit();

      adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));

      String logged =
          appender.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(logged).doesNotContain("esign:").doesNotContain("x_coord").doesNotContain("Asha");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void missingAnchorRefusesBeforeTheProviderIsEverCalled() {
    stubInit();

    // A blank draft has no text layer, so neither anchor can be located.
    assertThatThrownBy(() -> adapter.createSignRequest(twoSignerRequest(TestPdfs.singlePage())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("anchor");

    server.verify(0, postRequestedFor(urlEqualTo(INIT_URL)));
  }

  @Test
  void oversizedDocumentIsRefusedBeforeTheCall() {
    stubInit();
    // Just over the vendor's 14 MB ENCODED ceiling (base64 inflates by 4/3).
    byte[] huge = new byte[ZoopEsignProvider.MAX_ENCODED_DOCUMENT_BYTES / 4 * 3 + 1024];

    assertThatThrownBy(() -> adapter.createSignRequest(twoSignerRequest(huge)))
        .isInstanceOf(IllegalStateException.class);

    server.verify(0, postRequestedFor(urlEqualTo(INIT_URL)));
  }

  // --- status ----------------------------------------------------------------

  @Test
  void getStatusReadsAuthoritativePerSignerStatuses() {
    server.stubFor(
        get(urlPathEqualTo(GROUP_URL))
            .withQueryParam("group_id", equalTo("GRP-1"))
            .withHeader("app-id", equalTo(APP_ID))
            .willReturn(
                okJson(
                    """
                    {"success":true,"group_id":"GRP-1","transaction_status":"INPROGRESS",
                     "requests":[{"request_id":"REQ-1","status":"SIGNED"},
                                 {"request_id":"REQ-2","status":"INPROGRESS"}]}
                    """)));

    DocumentStatusView view = adapter.getStatus("GRP-1");

    assertThat(view.invitees()).hasSize(2);
    assertThat(view.invitees().get(0).providerInviteeId()).isEqualTo("REQ-1");
    assertThat(view.invitees().get(0).ordinal()).isZero();
    assertThat(view.invitees().get(0).status()).isEqualTo(InviteeStatus.SIGNED);
    // "INPROGRESS" is non-terminal -> PENDING, so a partially-signed transaction is a safe no-op.
    assertThat(view.invitees().get(1).status()).isEqualTo(InviteeStatus.PENDING);
  }

  @Test
  void unknownVendorStatusesFallBackToPendingRatherThanAnythingTerminal() {
    assertThat(ZoopEsignProvider.mapInviteeStatus("SIGNED")).isEqualTo(InviteeStatus.SIGNED);
    assertThat(ZoopEsignProvider.mapInviteeStatus("completed")).isEqualTo(InviteeStatus.SIGNED);
    assertThat(ZoopEsignProvider.mapInviteeStatus("REJECTED")).isEqualTo(InviteeStatus.REJECTED);
    assertThat(ZoopEsignProvider.mapInviteeStatus("FAILED")).isEqualTo(InviteeStatus.REJECTED);
    assertThat(ZoopEsignProvider.mapInviteeStatus("EXPIRED")).isEqualTo(InviteeStatus.EXPIRED);
    assertThat(ZoopEsignProvider.mapInviteeStatus("PENDING")).isEqualTo(InviteeStatus.PENDING);
    // The important one: a value the vendor invents tomorrow must not drive a terminal transition.
    assertThat(ZoopEsignProvider.mapInviteeStatus("SOMETHING_NEW"))
        .isEqualTo(InviteeStatus.PENDING);
    assertThat(ZoopEsignProvider.mapInviteeStatus(null)).isEqualTo(InviteeStatus.PENDING);
  }

  // --- download --------------------------------------------------------------

  private void stubGroupWithCompleteUrl(String completeSignedUrl) {
    server.stubFor(
        get(urlPathEqualTo(GROUP_URL))
            .willReturn(
                okJson(
                    "{\"success\":true,\"group_id\":\"GRP-1\",\"transaction_status\":\"SIGNED\","
                        + "\"complete_signed_url\":\""
                        + completeSignedUrl
                        + "\",\"requests\":[{\"request_id\":\"REQ-1\",\"status\":\"SIGNED\"}]}")));
  }

  @Test
  void downloadFetchesTheCompleteSignedDocumentAndTheAuditTrailWithDeclaredContentTypes() {
    stubGroupWithCompleteUrl(server.baseUrl() + "/files/complete.pdf");
    server.stubFor(
        get(urlPathEqualTo("/files/complete.pdf"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/pdf")
                    .withBody("SIGNEDPDF".getBytes(StandardCharsets.UTF_8))));
    server.stubFor(
        get(urlPathEqualTo(AUDIT_URL))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"trail\":[]}".getBytes(StandardCharsets.UTF_8))));

    SignedDocument document = adapter.download("GRP-1");

    assertThat(document.providerDocumentId()).isEqualTo("GRP-1");
    assertThat(new String(document.signedPdf(), StandardCharsets.UTF_8)).isEqualTo("SIGNEDPDF");
    assertThat(document.signedPdfContentType()).contains("application/pdf");
    assertThat(new String(document.auditTrail(), StandardCharsets.UTF_8)).contains("trail");
    assertThat(document.auditTrailContentType()).contains("application/json");
  }

  @Test
  void artifactUrlOnAnUnlistedHostIsRefused() {
    stubGroupWithCompleteUrl("https://evil.example.com/steal");

    assertThatThrownBy(() -> adapter.download("GRP-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider host");
  }

  @Test
  void aSecondProviderHostOnTheAllowlistIsAccepted() {
    // ZOOP's signed-document links live on a DIFFERENT host from the API, which is exactly why the
    // pin is an allowlist rather than a single host - but it stays an allowlist.
    adapter = adapterWith(List.of("api.zoop.example", host()));
    stubGroupWithCompleteUrl(server.baseUrl() + "/files/complete.pdf");
    server.stubFor(
        get(urlPathEqualTo("/files/complete.pdf"))
            .willReturn(aResponse().withBody("OK".getBytes(StandardCharsets.UTF_8))));
    server.stubFor(
        get(urlPathEqualTo(AUDIT_URL))
            .willReturn(aResponse().withBody("AUDIT".getBytes(StandardCharsets.UTF_8))));

    assertThat(new String(adapter.download("GRP-1").signedPdf(), StandardCharsets.UTF_8))
        .isEqualTo("OK");
  }

  @Test
  void anArtifactWithNoDeclaredContentTypeDefaultsToOctetStream() {
    stubGroupWithCompleteUrl(server.baseUrl() + "/files/complete.pdf");
    server.stubFor(
        get(urlPathEqualTo("/files/complete.pdf"))
            .willReturn(aResponse().withBody("BYTES".getBytes(StandardCharsets.UTF_8))));
    server.stubFor(
        get(urlPathEqualTo(AUDIT_URL))
            .willReturn(aResponse().withBody("AUDIT".getBytes(StandardCharsets.UTF_8))));

    SignedDocument document = adapter.download("GRP-1");

    assertThat(document.signedPdfContentType()).isEqualTo("application/octet-stream");
  }

  // --- extend / re-invite ----------------------------------------------------

  @Test
  void extendingAPendingTransactionReusesTheSameGroupIdSoNoSecondChargeIsIncurred() {
    server.stubFor(
        post(urlEqualTo("/contract/esign/v5/increase-expiry-time")).willReturn(okJson("{}")));

    adapter.extendExpiry("GRP-1", 10080);

    server.verify(
        1,
        postRequestedFor(urlEqualTo("/contract/esign/v5/increase-expiry-time"))
            .withRequestBody(matchingJsonPath("$.group_id", equalTo("GRP-1")))
            .withRequestBody(matchingJsonPath("$.txn_expiry_min", equalTo("10080"))));
    // Crucially: NOT a second /v5/init. A new transaction would be a new document and a new charge.
    server.verify(0, postRequestedFor(urlEqualTo(INIT_URL)));
  }

  @Test
  void resendingInvitationsReusesTheSameGroupId() {
    server.stubFor(
        post(urlEqualTo("/contract/esign/v5/send-esign-invitation")).willReturn(okJson("{}")));

    adapter.resendInvitations("GRP-1");

    server.verify(
        1,
        postRequestedFor(urlEqualTo("/contract/esign/v5/send-esign-invitation"))
            .withRequestBody(matchingJsonPath("$.group_id", equalTo("GRP-1"))));
    server.verify(0, postRequestedFor(urlEqualTo(INIT_URL)));
  }

  // --- redaction -------------------------------------------------------------

  @Test
  void neitherIdentifiersUrlsNorEkycFieldsReachTheLogs() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ZoopEsignProvider.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      server.stubFor(
          post(urlEqualTo(INIT_URL))
              .willReturn(
                  okJson(
                      """
                      {"success":true,"group_id":"GRP-SECRET-9876",
                       "webhook_security_key":"WHK-SECRET-1",
                       "expires_at":"2026-08-10T10:00:00Z",
                       "requests":[
                         {"request_id":"REQ-SECRET-1","signer_email":"asha@example.com",
                          "signing_order":1,"signing_url":"https://esign.zoop.plus/s/SECRETURL"},
                         {"request_id":"REQ-SECRET-2","signer_email":"tara@example.com",
                          "signing_order":2,"signing_url":"https://esign.zoop.plus/s/SECRETURL2"}]}
                      """)));
      server.stubFor(
          get(urlPathEqualTo(GROUP_URL))
              .willReturn(
                  okJson(
                      """
                      {"success":true,"group_id":"GRP-SECRET-9876","transaction_status":"SIGNED",
                       "requests":[{"request_id":"REQ-SECRET-1","status":"SIGNED",
                                    "signer":{"fetched_name":"ASHA KUMARI","given_name":"ASHA",
                                              "postal_code":"560001","name_match_score":"0.98"}}]}
                      """)));

      adapter.createSignRequest(twoSignerRequest(TestPdfs.withEsignAnchors()));
      adapter.getStatus("GRP-SECRET-9876");

      String logged =
          appender.list.stream()
              .map(e -> e.getFormattedMessage())
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(logged).doesNotContain("GRP-SECRET-9876"); // transaction id redacted
      assertThat(logged).doesNotContain("REQ-SECRET-1"); // per-signer id never logged
      assertThat(logged).doesNotContain("WHK-SECRET-1"); // the credential, never
      assertThat(logged).doesNotContain("SECRETURL"); // signing URLs are bearer capabilities
      // eKYC-derived signer data returned by the vendor must never surface in a log line.
      assertThat(logged).doesNotContain("ASHA KUMARI");
      assertThat(logged).doesNotContain("560001");
      assertThat(logged).doesNotContain("0.98");
      assertThat(logged).doesNotContain("asha@example.com");
    } finally {
      logger.detachAppender(appender);
    }
  }
}

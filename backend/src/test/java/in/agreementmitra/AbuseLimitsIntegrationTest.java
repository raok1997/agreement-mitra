package in.agreementmitra;

import static in.agreementmitra.support.CsrfMockMvc.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The route-class limits with the switch ON, at the shipped default values
 * (anonymous-surface-abuse-controls D10; tasks 10.4, 11.1, 11.7, 11.8, 11.9).
 *
 * <p>MockMvc never runs Tomcat's {@code RemoteIpValve}, so distinct clients are simulated by
 * setting the request's remote address directly ({@link #from}) -- which is exactly the value the
 * valve produces behind the trusted proxy. The valve itself is exercised over a real port in {@code
 * RecoveryIntegrationTest} and {@link UntrustedPeerIntegrationTest}. Each test uses its own
 * TEST-NET source and both limiters are reset between tests, so no test's traffic throttles
 * another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AbuseLimitsIntegrationTest {

  private static final String WEBHOOK_KEY = "it-limits-wh-mac";

  /**
   * Endpoints that consciously take the default class (authenticated or staff work). A new endpoint
   * that is in neither this list nor the class table fails {@link
   * #everyApiEndpointIsClassifiedOrConsciouslyDefault}.
   */
  private static final Set<String> DEFAULT_CLASS =
      Set.of(
          "GET /api/agreements",
          "PUT /api/agreements/{id}",
          "DELETE /api/agreements/{id}",
          "POST /api/agreements/{id}/claim",
          "GET /api/auth/me",
          "POST /api/signing/{agreementId}/request");

  @DynamicPropertySource
  static void limitsOn(DynamicPropertyRegistry registry) {
    registry.add("abuse.limits.enabled", () -> "true");
    registry.add("payment.razorpay.webhook-secret", () -> WEBHOOK_KEY);
  }

  @Autowired private MockMvc mockMvc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Autowired
  @Qualifier(AbuseControlsConfig.ROUTE_LIMITER)
  private SlidingWindowRateLimiter routeLimiter;

  @Autowired
  @Qualifier("recoveryRateLimiter")
  private SlidingWindowRateLimiter recoveryLimiter;

  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void resetLimiters() {
    routeLimiter.reset();
    recoveryLimiter.reset();
  }

  private static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  /** The status, or -1 when the handler threw past MVC -- which a limiter refusal never does. */
  private int status(MockHttpServletRequestBuilder request) {
    try {
      return mockMvc.perform(request).andReturn().getResponse().getStatus();
    } catch (Exception reachedTheHandler) {
      return -1;
    }
  }

  private static String previewBody() {
    return "{\"data\":{\"ownerName\":\"Asha Rao\",\"tenantName\":\"Bhaskar Rao\"}}";
  }

  private MockHttpServletRequestBuilder htmlPreview(String source) {
    return post("/api/templates/document/preview")
        .with(from(source))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_HTML)
        .content(previewBody());
  }

  private String createBody(String address) throws Exception {
    Map<String, Object> owner =
        Map.of(
            "firstName",
            "Asha",
            "lastName",
            "X",
            "fatherName",
            "Father Asha",
            "currentAddress",
            "1 Road",
            "email",
            "asha@example.com",
            "role",
            "OWNER");
    Map<String, Object> tenant =
        Map.of(
            "firstName",
            "Tara",
            "lastName",
            "X",
            "fatherName",
            "Father Tara",
            "currentAddress",
            "2 Road",
            "email",
            "tara@example.com",
            "role",
            "TENANT");
    return mapper.writeValueAsString(
        Map.of(
            "propertyAddress", address,
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers", List.of(owner, tenant)));
  }

  private MockHttpServletRequestBuilder create(String source, String address) throws Exception {
    return post("/api/agreements")
        .with(from(source))
        .contentType(MediaType.APPLICATION_JSON)
        .content(createBody(address));
  }

  // --- 11.1 ---------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder pdfPreview(String source) {
    return post("/api/templates/document/preview")
        .with(from(source))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_PDF)
        .content(previewBody());
  }

  @Test
  void aRenderFloodFromOneSourceIsRefusedWhileAnotherSourceStillSucceeds() {
    // No Gotenberg in this context, so each admitted render fails past the limiter (status -1 or a
    // 5xx); what matters is that none of the first 60 is a 429 and the 61st is.
    for (int i = 0; i < 60; i++) {
      assertThat(status(pdfPreview("203.0.113.10"))).as("render %d", i).isNotEqualTo(429);
    }
    assertThat(status(pdfPreview("203.0.113.10"))).isEqualTo(429);
    assertThat(status(pdfPreview("203.0.113.11"))).isNotEqualTo(429);
  }

  @Test
  void theHtmlLivePreviewIsNotHeldToTheRenderLimitAndNeverLocksOut() {
    // A slow typist on a phone fires one live preview per keystroke.
    for (int i = 0; i < 100; i++) {
      assertThat(status(htmlPreview("203.0.113.12"))).as("live preview %d", i).isEqualTo(200);
    }
  }

  // --- cross-site (D4) ---------------------------------------------------------------------------

  @Test
  void crossSiteRequestsAreRefusedAndSpendNoBudget() {
    String source = "203.0.113.13";
    String id = "3f2c1b9e-8d4a-4c1e-9f0a-1b2c3d4e5f60";
    // A hostile page embedding 150 GETs in the visitor's browser...
    for (int i = 0; i < 150; i++) {
      assertThat(
              status(
                  get("/api/agreements/" + id)
                      .with(from(source))
                      .header("Sec-Fetch-Site", "cross-site")))
          .isEqualTo(403);
    }
    // ...leaves the visitor's own capability-read budget untouched.
    assertThat(
            status(
                get("/api/agreements/" + id)
                    .with(from(source))
                    .header("Sec-Fetch-Site", "same-origin")))
        .isNotEqualTo(429);
  }

  // --- 11.7 ---------------------------------------------------------------------------------

  @Test
  void requestsRefusedForCsrfConsumeNoBudget() throws Exception {
    String source = "203.0.113.20";
    for (int i = 0; i < 15; i++) {
      String body =
          mockMvc
              .perform(create(source, "11.7 tokenless " + i))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).contains("urn:agreementmitra:problem:csrf");
    }
    // The anonymous-write budget (10/min) is untouched by the fifteen refusals above.
    for (int i = 0; i < 10; i++) {
      assertThat(status(create(source, "11.7 valid " + i).with(csrf())))
          .as("create %d", i)
          .isEqualTo(201);
    }
    assertThat(status(create(source, "11.7 over").with(csrf()))).isEqualTo(429);
  }

  // --- 11.8 ---------------------------------------------------------------------------------

  @Test
  void recoveryBeyondEveryRouteClassLimitStillAnswersItsUniformResponse() {
    for (int i = 0; i < 130; i++) {
      assertThat(
              status(
                  post("/api/agreements/recovery")
                      .with(from("203.0.113.30"))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reference\":\"AMZZZZZZZZZ\"}")))
          .as("recovery %d", i)
          .isEqualTo(202);
    }
  }

  @Test
  void correctlySignedWebhooksAboveTheDefaultRateAreNeverRefused() throws Exception {
    String body = "{\"event\":\"refund.processed\"}";
    String signature = hmacSha256Hex(body);
    for (int i = 0; i < 130; i++) {
      assertThat(
              status(
                  post("/api/webhooks/razorpay")
                      .with(from("203.0.113.40"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("X-Razorpay-Signature", signature)
                      .content(body)))
          .as("webhook %d", i)
          .isEqualTo(202);
    }
  }

  @Test
  void aWebhookWithAnInvalidSignatureIsRefusedRecordedAndLogsNoPayload() throws Exception {
    String marker = "PAYLOAD-MARKER-7f3a";
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      int status =
          status(
              post("/api/webhooks/razorpay")
                  .with(from("203.0.113.41"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("X-Razorpay-Signature", "forged")
                  .content("{\"event\":\"" + marker + "\"}"));

      assertThat(status).isEqualTo(401);
      assertThat(capture.lines())
          .singleElement()
          .asString()
          .contains("event=webhook_verification_failed")
          .contains("route=/api/webhooks/razorpay")
          .contains("source=203.0.113.0/24")
          .doesNotContain(marker)
          .doesNotContain("203.0.113.41");
    }
  }

  private static String hmacSha256Hex(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(WEBHOOK_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  // --- 11.9 ---------------------------------------------------------------------------------

  @Test
  void theLegitimateEndToEndFlowWithFourViewersTriggersNoLimit() throws Exception {
    String source = "203.0.113.50"; // everyone behind one NAT: the strictest case
    List<Integer> statuses = new ArrayList<>();

    // The client's catalog + CSRF bootstrap fetches.
    for (int i = 0; i < 5; i++) {
      statuses.add(status(get("/api/templates").with(from(source))));
      statuses.add(status(get("/api/jurisdictions").with(from(source))));
      statuses.add(status(get("/api/templates/form").with(from(source))));
    }
    for (int i = 0; i < 20; i++) {
      statuses.add(status(get("/api/auth/csrf").with(from(source))));
    }

    // Typing through the capture form at the ~600 ms debounce, then creating the agreement.
    for (int i = 0; i < 40; i++) {
      statuses.add(status(htmlPreview(source)));
    }
    String created =
        mockMvc
            .perform(create(source, "11.9 legitimate flow").with(csrf()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode agreement = mapper.readTree(created);
    String id = agreement.path("id").asText();
    assertThat(UUID.fromString(id)).isNotNull();

    // Contacts, finalise, pay -- statuses vary with payment config; none may be a 429.
    statuses.add(
        status(
            patch("/api/agreements/" + id + "/contacts")
                .with(from(source))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")));
    statuses.add(
        status(post("/api/agreements/" + id + "/finalise").with(from(source)).with(csrf())));
    for (int i = 0; i < 2; i++) {
      statuses.add(
          status(
              post("/api/agreements/" + id + "/payment/order")
                  .with(from(source))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}")));
    }
    statuses.add(
        status(
            post("/api/agreements/" + id + "/payment/callback")
                .with(from(source))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")));

    // Four viewers on the status page for a minute: an initial load each, then three polls of
    // three reads at the client's 20 s interval.
    for (int viewer = 0; viewer < 4; viewer++) {
      statuses.add(status(get("/api/agreements/" + id).with(from(source))));
      for (int poll = 0; poll < 3; poll++) {
        statuses.add(status(get("/api/agreements/" + id).with(from(source))));
        statuses.add(status(get("/api/agreements/" + id + "/payment").with(from(source))));
        statuses.add(status(get("/api/signing/" + id + "/progress").with(from(source))));
      }
    }

    assertThat(statuses).doesNotContain(429);
  }

  // --- 10.4 ---------------------------------------------------------------------------------

  @Test
  void everyApiEndpointIsClassifiedOrConsciouslyDefault() {
    List<String> unclassified = new ArrayList<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
        String path = pattern.getPatternString();
        if (!path.startsWith("/api/")) {
          continue;
        }
        for (var method : info.getMethodsCondition().getMethods()) {
          String endpoint = method.name() + " " + path;
          String sample = path.replaceAll("\\{[^}]+}", "3f2c1b9e-8d4a-4c1e-9f0a-1b2c3d4e5f60");
          assertThat(pattern.matches(PathContainer.parsePath(sample))).isTrue();
          RouteClass routeClass = RouteClassifier.classify(method.name(), sample).routeClass();
          boolean consciousDefault =
              DEFAULT_CLASS.contains(endpoint) || path.startsWith("/api/staff/");
          if (routeClass == RouteClass.DEFAULT && !consciousDefault) {
            unclassified.add(endpoint);
          }
          if (routeClass != RouteClass.DEFAULT && consciousDefault) {
            unclassified.add(endpoint + " (listed as default but classified " + routeClass + ")");
          }
        }
      }
    }
    assertThat(unclassified).as("classify these in RouteClassifier.TABLE").isEmpty();
  }
}

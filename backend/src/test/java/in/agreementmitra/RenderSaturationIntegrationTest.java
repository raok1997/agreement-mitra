package in.agreementmitra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static in.agreementmitra.support.CsrfMockMvc.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import in.agreementmitra.documents.HtmlPdfRenderer;
import in.agreementmitra.documents.RenderPriority;
import in.agreementmitra.support.HarnessTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A render flood is confined to rendering (anonymous-surface-abuse-controls D6, task 11.5). The
 * Gotenberg upstream is a stub that answers after a fixed delay, so an admitted render holds its
 * slot; slot counts are small (one general, one reserved for the paid stamp render, no waiting
 * room) so one render saturates the general pool.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RenderSaturationIntegrationTest {

  private static final String ROUTE = "/forms/chromium/convert/html";
  private static final int RENDER_MILLIS = 2000;

  // HTTP/1.1 only: the application's JDK client attempts an h2c upgrade, which real Gotenberg
  // ignores and WireMock would accept and then reset.
  private static final WireMockServer GOTENBERG =
      new WireMockServer(options().dynamicPort().http2PlainDisabled(true));

  static {
    GOTENBERG.start();
    GOTENBERG.stubFor(
        WireMock.post(urlEqualTo(ROUTE))
            .willReturn(
                aResponse()
                    .withFixedDelay(RENDER_MILLIS)
                    .withBody("%PDF-1.4 stub".getBytes(StandardCharsets.ISO_8859_1))));
  }

  @DynamicPropertySource
  static void smallRenderPool(DynamicPropertyRegistry registry) {
    registry.add("gotenberg.url", GOTENBERG::baseUrl);
    registry.add("gotenberg.max-concurrent-renders", () -> "2");
    registry.add("gotenberg.reserved-renders", () -> "1");
    registry.add("gotenberg.max-waiters", () -> "0");
    registry.add("gotenberg.admission-wait", () -> "PT0.2S");
  }

  @AfterAll
  static void stop() {
    GOTENBERG.stop();
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private HtmlPdfRenderer renderer;

  private final ObjectMapper mapper = new ObjectMapper();

  private MockHttpServletRequestBuilder pdfPreview() {
    return post("/api/templates/document/preview")
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_PDF)
        .content("{\"data\":{\"ownerName\":\"Asha Rao\",\"tenantName\":\"Bhaskar Rao\"}}");
  }

  private String createAgreement() throws Exception {
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
    String body =
        mapper.writeValueAsString(
            Map.of(
                "propertyAddress", "11.5 saturation",
                "monthlyRent", "25000.00",
                "securityDeposit", "50000.00",
                "startDate", "2026-01-01",
                "endDate", "2026-12-01",
                "signers", List.of(owner, tenant)));
    String created =
        mockMvc
            .perform(
                post("/api/agreements")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(created).path("id").asText();
  }

  @Test
  void aRenderFloodIsRefused503WhileDatabaseReadsAndThePaidStampRenderStillServe()
      throws Exception {
    String agreementId = createAgreement();
    GOTENBERG.resetRequests();

    // One preview holds the only general slot for RENDER_MILLIS.
    CompletableFuture<MockHttpServletResponse> holding =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return mockMvc.perform(pdfPreview()).andReturn().getResponse();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> GOTENBERG.findAll(postRequestedFor(urlEqualTo(ROUTE))).size() == 1);

    // The next render is refused at once: the waiting room is full (it has no seats).
    long started = System.nanoTime();
    MockHttpServletResponse refused = mockMvc.perform(pdfPreview()).andReturn().getResponse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    assertThat(refused.getStatus()).isEqualTo(503);
    assertThat(refused.getHeader("Retry-After")).isNotBlank();
    assertThat(refused.getContentAsString()).contains("urn:agreementmitra:problem:render-busy");

    // A DB-backed endpoint that does not render keeps serving.
    assertThat(
            mockMvc
                .perform(get("/api/agreements/" + agreementId))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    // The paid stamp render is admitted to the reserved slot and completes.
    byte[] stamped = renderer.toPdf("<p>stamp</p>", "AMSTAMPREF", RenderPriority.FULFILMENT);
    assertThat(new String(stamped, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

    assertThat(holding.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
  }
}

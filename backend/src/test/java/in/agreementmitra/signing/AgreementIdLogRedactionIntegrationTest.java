package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.AgreementIds;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.LeegalityWireMock;
import in.agreementmitra.support.LogCapture;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.SigningRequests;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.StampUploads;
import in.agreementmitra.support.TemplateCatalogFixture;
import in.agreementmitra.support.TestPdfs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An agreement's lifecycle at DEBUG leaks no raw agreement id into any log line or logged throwable
 * (agreement-id-debug-logging 6.3, design D6.1).
 *
 * <p>Drives the real order -- create, draft upload, finalise (order placed, {@code PDF_GENERATED}
 * signing request), staff waive, e-Stamp intake (which reaches the stored-draft stamping fallback),
 * staff eSign initiation against a WireMock Leegality -- with {@code in.agreementmitra} pinned to
 * DEBUG and every event reaching the root logger captured. Nothing on this path is asynchronous, so
 * the capture is complete when the last request returns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementIdLogRedactionIntegrationTest {

  private static final WireMockServer WIREMOCK = LeegalityWireMock.start();

  @DynamicPropertySource
  static void leegalityProperties(DynamicPropertyRegistry registry) {
    LeegalityWireMock.register(registry, WIREMOCK);
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  @Autowired private TestRestTemplate rest;
  @Autowired private ApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  @Test
  void aFullLifecycleAtDebugLogsOnlyTheRedactedId() {
    TemplateCatalogFixture.seedEligible(jdbc);
    WIREMOCK.resetAll();
    LeegalityWireMock.stubCreate(WIREMOCK, "DOC-REDACT-1");
    String staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "redaction-staff-" + UUID.randomUUID());

    UUID agreementId = create();
    uploadDraft(agreementId);
    assertThat(
            rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(waive(agreementId, staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    StampUploads.upload(rest, jdbc, staffToken, agreementId);
    ResponseEntity<String> signing = SigningRequests.post(rest, context, agreementId);

    assertThat(signing.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("SIGN_REQUESTED");

    String prefix = AgreementIds.redact(agreementId);
    List<String> messages = logs.messages();
    // Positive first: the DEBUG level took effect and the redacted form is what prints.
    assertThat(messages)
        .anyMatch(m -> m.contains("Draft stored for agreement " + prefix))
        .anyMatch(m -> m.contains("Stored object drafts/" + prefix + ".pdf"))
        .anyMatch(m -> m.contains("created for agreement " + prefix))
        .anyMatch(m -> m.contains("Stamping agreement " + prefix));
    assertThat(messages).noneMatch(m -> m.contains(agreementId.toString()));
    assertThat(logs.throwableMessages()).noneMatch(m -> m.contains(agreementId.toString()));
  }

  @Test
  void theCauseChainWalkerReachesNestedCauses() {
    UUID id = UUID.randomUUID();
    LoggingEvent event =
        new LoggingEvent(
            getClass().getName(),
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("x"),
            Level.ERROR,
            "outer",
            new IllegalStateException("wrapper", new RuntimeException("inner " + id)),
            null);

    List<String> chain = LogCapture.throwableMessages(List.<ILoggingEvent>of(event));

    assertThat(chain).anyMatch(m -> m.contains("wrapper"));
    assertThat(chain).anyMatch(m -> m.contains("inner " + id));
  }

  private UUID create() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "12 MG Road, Bengaluru",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    signer("Asha", "Owner", "asha@example.com", "OWNER"),
                    signer("Tara", "Tenant", "tara@example.com", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private static Map<String, Object> signer(
      String firstName, String lastName, String email, String role) {
    return Map.of(
        "firstName", firstName,
        "lastName", lastName,
        "fatherName", "Ravi " + lastName,
        "currentAddress", "1 A St",
        "email", email,
        "role", role);
  }

  private void uploadDraft(UUID agreementId) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.singlePage()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> waive(UUID agreementId, String staffToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(staffToken));
    return rest.exchange(
        "/api/staff/payments/" + agreementId + "/waive",
        HttpMethod.POST,
        new HttpEntity<>(null, headers),
        String.class);
  }
}

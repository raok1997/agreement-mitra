package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.CsrfTestInterceptor;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.TestPdfs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The request-body ceiling over a real port (anonymous-surface-abuse-controls D9, task 11.4). A
 * real HTTP client is needed for the chunked case: MockMvc always declares a length. Bodies stay
 * between 1 MiB and 2 MiB, since Tomcat resets the connection rather than answering once a refused
 * body passes {@code maxSwallowSize}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RequestBodyGuardIntegrationTest {

  private static final int OVER_CEILING = 1_536 * 1024; // 1.5 MiB

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private static String oversizedCreateBody(String address) {
    char[] padding = new char[OVER_CEILING];
    Arrays.fill(padding, 'x');
    return "{\"propertyAddress\":\""
        + address
        + new String(padding)
        + "\",\"monthlyRent\":\"25000.00\"}";
  }

  private Integer agreementsLike(String address) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM agreement WHERE property_address LIKE ?",
        Integer.class,
        address + "%");
  }

  private static void assertPayloadTooLarge(int status, String body) {
    assertThat(status).isEqualTo(413);
    assertThat(body)
        .contains("urn:agreementmitra:problem:payload-too-large")
        .doesNotContain("xxxxxxxx");
  }

  @Test
  void anOverCeilingCreateWithADeclaredLengthIsRefused413AndPersistsNothing() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/agreements",
            new HttpEntity<>(oversizedCreateBody("11.4 declared "), headers),
            String.class);

    assertPayloadTooLarge(response.getStatusCode().value(), response.getBody());
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
    assertThat(agreementsLike("11.4 declared")).isZero();
  }

  @Test
  void anOverCeilingCreateStreamedWithoutALengthIsRefused413AndPersistsNothing() throws Exception {
    byte[] body = oversizedCreateBody("11.4 chunked ").getBytes(StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agreements"))
            .header("Content-Type", "application/json")
            .header("Cookie", CsrfTestInterceptor.COOKIE_NAME + "=" + CsrfTestInterceptor.TOKEN)
            .header(CsrfTestInterceptor.HEADER_NAME, CsrfTestInterceptor.TOKEN)
            // An InputStream publisher has no known length, so the body goes out chunked.
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
            .build();
    HttpResponse<String> response =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.headers().firstValue("Content-Length")).isEmpty();
    assertPayloadTooLarge(response.statusCode(), response.body());
    assertThat(agreementsLike("11.4 chunked")).isZero();
  }

  @Test
  void aDraftUploadAboveTheGeneralCeilingButWithinItsOwnIsAccepted() {
    UUID agreementId = createAgreement();
    byte[] pdf = TestPdfs.singlePage();
    byte[] padded = Arrays.copyOf(pdf, pdf.length + OVER_CEILING); // still begins %PDF-

    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(padded) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private UUID createAgreement() {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "11.4 upload",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers",
                List.of(
                    signer("Asha", "asha@example.com", "OWNER"),
                    signer("Tara", "tara@example.com", "TENANT")));
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private static Map<String, Object> signer(String first, String email, String role) {
    return Map.of(
        "firstName",
        first,
        "lastName",
        "X",
        "fatherName",
        "Father " + first,
        "currentAddress",
        "Addr " + first,
        "email",
        email,
        "role",
        role);
  }
}

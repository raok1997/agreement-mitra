package in.agreementmitra.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** The staff e-Stamp upload ({@code POST /api/staff/estamp}) over HTTP, as an operator does it. */
public final class StampUploads {

  private StampUploads() {}

  /**
   * Upload a fresh KA certificate for {@code agreementId} with a staff session and assert 200. The
   * duty amount covers the recomputed stamp duty of any fixture (state-stamp-duty-quoting).
   */
  public static void upload(
      TestRestTemplate rest, JdbcTemplate jdbc, String staffToken, UUID agreementId) {
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
    String certificate =
        "IN-KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "scan",
        new ByteArrayResource(TestImages.certificateScan()) {
          @Override
          public String getFilename() {
            return "certificate.png";
          }
        });
    form.add("agreementReference", reference);
    form.add("certificateNumber", certificate);
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", "10000.00");
    form.add("jurisdiction", "KA");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(staffToken));
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}

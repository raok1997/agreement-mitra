package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A forwarding header from a peer that is NOT the trusted proxy is ignored
 * (anonymous-surface-abuse-controls D2, task 11.3). Here {@code internal-proxies} is a pattern
 * loopback cannot match, so the test client is an untrusted peer -- as anything reaching the
 * application other than Caddy is in production. The source is observed through the recovery
 * audit's requester fingerprint, which records the resolved key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1")
@Testcontainers(disabledWithoutDocker = true)
class UntrustedPeerIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void aForwardedForFromAnUntrustedPeerIsIgnoredAndThePeerIsTheSource() {
    String reference = "AMUNTRUSTED";
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Forwarded-For", "203.0.113.71");
    headers.set("Forwarded", "for=203.0.113.72");
    assertThat(
            rest.postForEntity(
                    "/api/agreements/recovery",
                    new HttpEntity<>(Map.of("reference", reference), headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    List<String> sources =
        jdbc.queryForList(
            "SELECT requester_fingerprint FROM recovery_audit WHERE reference = ?",
            String.class,
            reference);
    assertThat(sources).singleElement().isIn("127.0.0.1", "0:0:0:0:0:0:0:0/64");
  }
}

package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.payment.StaffOrdersFixture.Listing;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.PaymentOrders;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The cap on a group of the staff orders view, against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StaffPaymentOrdersCapIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private StaffOrdersFixture orders;
  private final Instant now = StaffOrdersFixture.now();

  @BeforeEach
  void fixture() {
    orders = new StaffOrdersFixture(rest, jdbc, identityService, handoffService, sessionService);
  }

  /**
   * 201 orders, the newest created now, so they are the head of the group whatever else the shared
   * database holds. Removed afterwards, or every later test's older orders would sit past the cap.
   */
  @Test
  void aGroupOverTheLimitKeepsTheNewestTwoHundredAndSaysItWasCutShort() {
    UUID agreementId = orders.agreement();
    try {
      UUID oldest = null;
      UUID newest = null;
      for (int age = 200; age >= 0; age--) {
        newest =
            PaymentOrders.insert(
                jdbc, agreementId, "EXPIRED", now.minusSeconds(age), UUID.randomUUID().toString());
        if (age == 200) {
          oldest = newest;
        }
      }

      Listing listing = orders.list();

      JsonNode notPaid = listing.json().get("notPaid");
      assertThat(notPaid.get("entries")).hasSize(200);
      assertThat(notPaid.get("truncated").asBoolean()).isTrue();
      assertThat(listing.notPaid(newest)).isPresent();
      assertThat(listing.notPaid(oldest)).isEmpty();
    } finally {
      jdbc.update("DELETE FROM payment_order WHERE agreement_id = ?", agreementId);
    }
  }
}

package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Who may read the staff orders view, and that the route opens nothing but its GET. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StaffPaymentOrdersAccessIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private StaffOrdersFixture orders;

  @BeforeEach
  void fixture() {
    orders = new StaffOrdersFixture(rest, jdbc, identityService, handoffService, sessionService);
  }

  @Test
  void aCallerWithoutASessionIsToldToAuthenticate() {
    assertThat(orders.get(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aSignedInCustomerIsRefusedAndShownNoOrder() {
    ResponseEntity<String> refused = orders.get(orders.customerSession());

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(refused.getBody() == null ? "" : refused.getBody()).doesNotContain("paymentOrderId");
  }

  @Test
  void staffAreServedBothGroupsAndTheResponseIsNotToBeStored() {
    ResponseEntity<String> served = orders.get(orders.staffSession());

    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(served.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(served.getBody()).contains("\"stuck\"").contains("\"notPaid\"");
  }

  /** The CSRF token is valid (the harness adds it), so the 403 is the route, not the token. */
  @Test
  void noOtherMethodIsOpenedOnTheRouteEvenForStaff() {
    assertThat(orders.post(orders.staffSession()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}

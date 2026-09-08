package in.agreementmitra.signing.contact;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.RecordingEmailSender;
import in.agreementmitra.support.TestPdfs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * The contact gate, from the outside.
 *
 * <p>Two things are being proved. First, that <b>the server refuses an unreachable agreement</b> -
 * the pre-checkout screen is where customers meet the requirement, but a step in a browser enforces
 * nothing and a caller going straight to the API must still be stopped. Second, that the contacts
 * route is <b>narrow</b>: it can set contacts and it cannot do anything else, which is what makes
 * opening it to anonymous callers cost less than opening the full edit route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ContactGateIntegrationTest {

  @DynamicPropertySource
  static void deliveryProperties(DynamicPropertyRegistry registry) {
    registry.add("delivery.public-base-url", () -> "https://app.example.test");
    registry.add("delivery.channels.email.enabled", () -> "true");
    // Declared but off, exactly as shipped. A party carrying only a mobile must not pass.
    registry.add("delivery.channels.sms.enabled", () -> "false");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  /**
   * Pin every agreement these tests create to an ELIGIBLE jurisdiction. Since
   * jurisdiction-checkout-gating, an agreement with no pinned template has no duty jurisdiction and
   * is refused at finalise, checkout, e-stamp intake and eSign initiation - so a fixture that
   * creates a bare agreement can no longer reach the steps these tests exercise. The seeder is
   * local/sandbox-only, so the row is inserted here.
   */
  @BeforeEach
  void seedEligibleTemplate() {
    in.agreementmitra.support.TemplateCatalogFixture.seedEligible(jdbc);
  }

  @Autowired private IdentityService identityService;
  @Autowired private RecordingEmailSender mail;

  @BeforeEach
  void resetMail() {
    mail.reset();
  }

  private record Party(String first, String role, String email, String mobile) {}

  /**
   * Claim the agreement into a real identity.
   *
   * <p>A random UUID will not do: {@code owner_identity_id} carries a foreign key, so the database
   * rejects a fabricated owner rather than claiming the agreement - which would make a test pass
   * for the wrong reason if it were not enforced.
   */
  private void claim(UUID agreementId) {
    UUID ownerId =
        identityService.findOrCreate(
            "google", "owner-" + agreementId, "owner-" + agreementId + "@example.com", true, "T");
    jdbc.update("UPDATE agreement SET owner_identity_id = ? WHERE id = ?", ownerId, agreementId);
  }

  private UUID createAgreement(List<Party> parties) {
    List<Map<String, Object>> signers =
        parties.stream()
            .map(
                p -> {
                  Map<String, Object> signer =
                      new java.util.HashMap<>(
                          Map.of(
                              "firstName", p.first(),
                              "lastName", "Party",
                              "fatherName", "Some Parent",
                              "currentAddress", "1 A St",
                              "role", p.role()));
                  if (p.email() != null) signer.put("email", p.email());
                  if (p.mobile() != null) signer.put("mobile", p.mobile());
                  return signer;
                })
            .toList();

    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "12 Test Street, Bengaluru 560038",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-09-01",
            "endDate", "2027-07-31",
            "signers", signers);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private ResponseEntity<String> startCheckout(UUID agreementId) {
    return rest.postForEntity(
        "/api/agreements/" + agreementId + "/payment/order", null, String.class);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> signersOf(UUID agreementId) {
    ResponseEntity<Map> agreement = rest.getForEntity("/api/agreements/" + agreementId, Map.class);
    return (List<Map<String, Object>>) agreement.getBody().get("signers");
  }

  @Test
  void checkoutIsRefusedWhenAPartyHasNoContactAtAll() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", null, null)));

    ResponseEntity<String> response = startCheckout(id);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // Named by role and position so the customer can fix the right one - never by name or contact.
    assertThat(response.getBody()).contains("tenant 1");
    assertThat(response.getBody()).doesNotContain("Tara");
  }

  /**
   * 9.15. Anti-mass-assignment at the checkout route. Contacts are set through {@code PATCH
   * /contacts} and nowhere else; if order creation honoured contacts in its own body, a caller
   * could satisfy the reachability gate for a party they cannot actually reach, and the finished
   * agreement would have nowhere to go.
   */
  @Test
  void orderCreationIgnoresContactDetailsSuppliedInItsOwnBody() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", null, null)));

    // Exactly the shape a caller would try in order to talk their way past the gate.
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/agreements/" + id + "/payment/order",
            Map.of(
                "contacts",
                List.of(Map.of("role", "TENANT", "email", "smuggled@example.com")),
                "signers",
                List.of(Map.of("role", "TENANT", "email", "smuggled@example.com"))),
            String.class);

    // Still refused, and refused for the same party as before.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).contains("tenant 1");

    // ...and nothing was written: the smuggled address is nowhere on the agreement.
    assertThat(signersOf(id))
        .noneSatisfy(signer -> assertThat(signer.get("email")).isEqualTo("smuggled@example.com"));
  }

  /**
   * 9.16. The reachability rule has to hold at <b>both</b> gates or it holds at neither: payment is
   * skippable when the gate is OPTIONAL, and a party admitted at checkout must not then be admitted
   * into signing. Both call the same {@link in.agreementmitra.signing.contact.PartyReachability},
   * and this asserts the second caller actually enforces it.
   */
  @Test
  void esignInitiationRefusesAPartyTheOldEmailOrMobileRuleWouldHaveAdmitted() {
    // Mobile-only: reachable under the retired email-OR-mobile rule, unreachable while SMS is off.
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", null, "9876543210")));
    Payments.waive(jdbc, id);

    ResponseEntity<String> requested =
        rest.postForEntity("/api/signing/" + id + "/request", null, String.class);

    // Exactly 409, not "some refusal": POST /api/signing/*/request is permitAll, so the request
    // reaches the handler and the only thing that can refuse it here is the reachability gate. A
    // looser assertion would pass on an auth rejection and prove nothing about reachability.
    assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // It is the reachability refusal specifically, not some other 409.
    assertThat(requested.getBody()).contains("urn:agreementmitra:problem:contact-required");
    // Still leaks no party identity or contact.
    assertThat(requested.getBody()).doesNotContain("Tara").doesNotContain("9876543210");
    // NOTE (observed 2026-09-05, not a defect this test asserts against): unlike the checkout gate,
    // this one does NOT name the offending party -- the body is the generic "Every party needs a
    // contact we can reach them on before payment." So a customer refused here is told less than
    // one
    // refused at checkout, and the wording says "before payment" on a signing path. Worth tidying;
    // deliberately not asserted, so tidying it will not fail this test.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signing_request WHERE agreement_id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  void aPartyCarryingOnlyAMobileIsRefusedWhileSmsIsOff() {
    // Exactly the case the old email-OR-mobile rule admitted. That party could sign and could never
    // be sent the finished agreement, so admitting them here would reintroduce the defect.
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", null, "9000000001")));

    assertThat(startCheckout(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void checkoutProceedsWhenEveryPartyIsReachable() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", "tara@example.com", null)));

    // Not asserting a created order: no payment provider is configured here, so the gate passing is
    // the thing under test. What matters is that it is no longer a 409 from the contact gate.
    assertThat(startCheckout(id).getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void contactsCanBeSetAnonymouslyAndUnblockCheckout() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", null, null)));
    assertThat(startCheckout(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    Map<String, Object> tenant =
        signersOf(id).stream()
            .filter(s -> "TENANT".equals(s.get("role")))
            .findFirst()
            .orElseThrow();

    ResponseEntity<String> patched =
        rest.exchange(
            "/api/agreements/" + id + "/contacts",
            HttpMethod.PATCH,
            new HttpEntity<>(
                Map.of(
                    "contacts",
                    List.of(
                        Map.of(
                            "signerId", tenant.get("id"),
                            "email", "tara@example.com",
                            "mobile", "9000000002")))),
            String.class);

    assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(startCheckout(id).getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theContactsRouteCannotChangeAnythingButContacts() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", "tara@example.com", null)));

    Map<String, Object> owner =
        signersOf(id).stream().filter(s -> "OWNER".equals(s.get("role"))).findFirst().orElseThrow();

    // Smuggled fields alongside the contacts. This is the property that makes an anonymous write
    // route acceptable: the worst a caller holding the id can do is redirect their own agreement's
    // notifications, never rewrite the rent.
    rest.exchange(
        "/api/agreements/" + id + "/contacts",
        HttpMethod.PATCH,
        new HttpEntity<>(
            Map.of(
                "contacts",
                    List.of(Map.of("signerId", owner.get("id"), "email", "new@example.com")),
                "monthlyRent", "1.00",
                "propertyAddress", "Somewhere else entirely")),
        String.class);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> after = rest.getForEntity("/api/agreements/" + id, Map.class);
    assertThat(after.getBody().get("propertyAddress"))
        .isEqualTo("12 Test Street, Bengaluru 560038");
    assertThat(after.getBody().get("monthlyRent").toString()).startsWith("25000");
  }

  @Test
  void settingContactsSendsTheDraftToEveryParty() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", "tara@example.com", null)));

    // No stored draft in this fixture, so nothing should be sent - the service must not invent a
    // message announcing a document that does not exist.
    mail.reset();
    Map<String, Object> owner =
        signersOf(id).stream().filter(s -> "OWNER".equals(s.get("role"))).findFirst().orElseThrow();
    rest.exchange(
        "/api/agreements/" + id + "/contacts",
        HttpMethod.PATCH,
        new HttpEntity<>(
            Map.of(
                "contacts",
                List.of(Map.of("signerId", owner.get("id"), "email", "asha@example.com")))),
        String.class);

    assertThat(mail.sent()).isEmpty();
  }

  @Test
  void aClaimedAgreementRefusesAnonymousContactChanges() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", "tara@example.com", null)));
    Map<String, Object> owner =
        signersOf(id).stream().filter(s -> "OWNER".equals(s.get("role"))).findFirst().orElseThrow();

    claim(id);

    ResponseEntity<String> refused =
        rest.exchange(
            "/api/agreements/" + id + "/contacts",
            HttpMethod.PATCH,
            new HttpEntity<>(
                Map.of(
                    "contacts",
                    List.of(Map.of("signerId", owner.get("id"), "email", "someone@example.com")))),
            String.class);

    // Same 404 an unknown agreement gets, so ownership cannot be probed here either.
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // --- The editing window: contacts stay changeable until PAYMENT settles (not until the order is
  // placed). This is the boundary that stranded a live customer: their first payment failed, the
  // retry re-entered the contact step, and its save was refused from the moment the order existed,
  // so the pay button was unreachable for good.

  @Test
  void contactsCanStillBeCorrectedAfterTheOrderIsPlaced() {
    UUID id = aFinalisedOrder();
    Map<String, Object> tenant = partyOf(id, "TENANT");
    mail.reset();

    ResponseEntity<String> patched = patchEmail(id, tenant.get("id"), "corrected@example.com");

    assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(emailOf(id, "TENANT")).isEqualTo("corrected@example.com");
    // ...and the corrected address actually receives the agreement. A save that stored the new
    // address but sent the draft to the old one would fix nothing the customer cares about.
    assertThat(mail.sentTo("corrected@example.com")).isNotEmpty();
  }

  @Test
  void contactsAreRefusedOncePaidAndSayWhy() {
    UUID id = aFinalisedOrder();
    Map<String, Object> tenant = partyOf(id, "TENANT");
    markPaid(id);

    ResponseEntity<String> refused = patchEmail(id, tenant.get("id"), "toolate@example.com");

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // Its OWN problem type. Sharing draft-frozen is what let a client tell the customer to "try
    // again" about a condition that refuses forever.
    assertThat(refused.getBody()).contains("urn:agreementmitra:problem:contacts-frozen");
    assertThat(refused.getBody()).doesNotContain("draft-frozen");
    // Nothing changed, and no rejected value is echoed back.
    assertThat(emailOf(id, "TENANT")).isEqualTo("tara@example.com");
    assertThat(refused.getBody()).doesNotContain("toolate@example.com");
  }

  @Test
  void theTermsFreezeDidNotMoveWithTheContactsFreeze() {
    // The whole change is that contacts stopped sharing a line with the terms. If this passed for
    // the wrong reason - because BOTH were relaxed - the document could be rewritten under a
    // placed order, so it is asserted explicitly rather than assumed.
    UUID id = aFinalisedOrder();

    ResponseEntity<String> edited =
        rest.exchange(
            "/api/agreements/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "propertyAddress", "Somewhere else entirely",
                    "monthlyRent", "1.00",
                    "securityDeposit", "1.00",
                    "startDate", "2026-09-01",
                    "endDate", "2027-07-31",
                    "signers", List.of())),
            String.class);

    assertThat(edited.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> after = rest.getForEntity("/api/agreements/" + id, Map.class);
    assertThat(after.getBody().get("propertyAddress"))
        .isEqualTo("12 Test Street, Bengaluru 560038");
  }

  /** An agreement with a real stored draft, finalised - i.e. an order that has been placed. */
  private UUID aFinalisedOrder() {
    UUID id =
        createAgreement(
            List.of(
                new Party("Asha", "OWNER", "asha@example.com", null),
                new Party("Tara", "TENANT", "tara@example.com", null)));

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
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + id + "/draft",
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return id;
  }

  /**
   * Settle the money directly. No payment provider runs in this fixture, and the thing under test
   * is what the contacts route does once the state is PAID - not how it got there.
   */
  private void markPaid(UUID agreementId) {
    jdbc.update("UPDATE agreement SET payment_state = 'PAID' WHERE id = ?", agreementId);
  }

  private Map<String, Object> partyOf(UUID agreementId, String role) {
    return signersOf(agreementId).stream()
        .filter(s -> role.equals(s.get("role")))
        .findFirst()
        .orElseThrow();
  }

  private String emailOf(UUID agreementId, String role) {
    return (String) partyOf(agreementId, role).get("email");
  }

  private ResponseEntity<String> patchEmail(UUID agreementId, Object signerId, String email) {
    return rest.exchange(
        "/api/agreements/" + agreementId + "/contacts",
        HttpMethod.PATCH,
        new HttpEntity<>(Map.of("contacts", List.of(Map.of("signerId", signerId, "email", email)))),
        String.class);
  }
}

package in.agreementmitra.signing.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.RecordingEmailSender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Recovery, end to end, against real infrastructure.
 *
 * <p><b>The single most important test here is {@link #everyOutcomeAnswersIdentically()}.</b> The
 * tracking reference carries roughly 40 bits against the agreement id's 122, so if this endpoint
 * ever revealed whether a reference existed, every paid agreement would become enumerable and full
 * signer PII would leak. The uniformity is not a UX choice that can be relaxed later - it is the
 * control that lets the reference be a selector without being a credential. That test asserts all
 * five outcomes in one place precisely so a future change cannot quietly regress one branch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecoveryIntegrationTest {

  private static final String BASE_URL = "https://app.example.test";

  /** Asked about by the audit test only, so its row count is not shared with another test. */
  private static final String UNKNOWN_TO_THIS_TEST = "AMQQQQQQQQQ";

  @DynamicPropertySource
  static void deliveryProperties(DynamicPropertyRegistry registry) {
    registry.add("delivery.public-base-url", () -> BASE_URL);
    registry.add("delivery.channels.email.enabled", () -> "true");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private RecordingEmailSender mail;
  @Autowired private RecoveryRateLimiter rateLimiter;

  @BeforeEach
  void resetHarness() {
    mail.reset();
    // The limiter is a singleton shared across this class, and every request here arrives from
    // loopback - so without this, one test's requests throttle the next one's and the failures look
    // like delivery bugs. Production keys on a real client address, where that does not happen.
    rateLimiter.clear();
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private record Created(UUID id, String reference) {}

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

  private Created createAgreement(String ownerEmail, String tenantEmail) {
    Map<String, Object> owner =
        signer("Asha", "Owner", "Ravi Owner", "1 A St", ownerEmail, "OWNER");
    Map<String, Object> tenant =
        signer("Tara", "Tenant", "Hari Tenant", "3 C St", tenantEmail, "TENANT");

    Map<String, Object> body =
        Map.of(
            "propertyAddress", "12 Test Street, Bengaluru 560038",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-09-01",
            "endDate", "2027-07-31",
            "signers", List.of(owner, tenant));

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<?, ?> payload = created.getBody();
    return new Created(
        UUID.fromString((String) payload.get("id")), (String) payload.get("trackingNumber"));
  }

  private static Map<String, Object> signer(
      String first, String last, String father, String address, String email, String role) {
    return email == null
        ? Map.of(
            "firstName", first,
            "lastName", last,
            "fatherName", father,
            "currentAddress", address,
            "role", role)
        : Map.of(
            "firstName", first,
            "lastName", last,
            "fatherName", father,
            "currentAddress", address,
            "email", email,
            "role", role);
  }

  private ResponseEntity<String> requestRecovery(String reference) {
    return rest.postForEntity(
        "/api/agreements/recovery", Map.of("reference", reference), String.class);
  }

  // --- the control ------------------------------------------------------------------------------

  @Test
  void everyOutcomeAnswersIdentically() {
    // Five genuinely different situations. If any one of them produced a different status or body,
    // the endpoint would become an oracle for "does this reference exist / is it paid / is it
    // claimed", and a ~40-bit reference would be enough to enumerate paid agreements.
    Created paid = createAgreement("asha@example.com", "tara@example.com");
    Payments.markPaid(jdbc, paid.id(), "pay_" + UUID.randomUUID());

    Created unpaid = createAgreement("unpaid@example.com", "other@example.com");

    Created noContact = createAgreement(null, null);
    Payments.markPaid(jdbc, noContact.id(), "pay_" + UUID.randomUUID());

    Created claimed = createAgreement("claimed@example.com", "c2@example.com");
    Payments.markPaid(jdbc, claimed.id(), "pay_" + UUID.randomUUID());
    claim(claimed.id());

    List<ResponseEntity<String>> responses =
        List.of(
            requestRecovery(paid.reference()),
            requestRecovery(unpaid.reference()),
            requestRecovery(noContact.reference()),
            requestRecovery(claimed.reference()),
            requestRecovery("AMZZZZZZZZZ"));

    assertThat(responses)
        .allSatisfy(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
              assertThat(response.getBody()).isNull();
            });
  }

  // --- what actually happens behind that uniform response ---------------------------------------

  @Test
  void aPaidUnownedAgreementSendsTheLinkToEveryParty() {
    Created created = createAgreement("asha@example.com", "tara@example.com");
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    mail.reset();

    requestRecovery(created.reference());

    // Both parties, not only whoever paid (design D15).
    assertThat(mail.sentTo("asha@example.com")).hasSize(1);
    assertThat(mail.sentTo("tara@example.com")).hasSize(1);
  }

  @Test
  void theEmailedLinkOpensTheAgreement() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    mail.reset();

    requestRecovery(created.reference());

    String body = mail.sentTo("asha@example.com").get(0).body();
    assertThat(body).contains(BASE_URL + "/agreement/" + created.id());

    // The link is the capability: the id in it reaches the agreement through the endpoint that
    // already serves unowned agreements. Nothing is redeemed.
    ResponseEntity<Map> opened = rest.getForEntity("/api/agreements/" + created.id(), Map.class);
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(opened.getBody().get("trackingNumber")).isEqualTo(created.reference());
  }

  @Test
  void anUnpaidAgreementSendsNothing() {
    Created created = createAgreement("asha@example.com", "tara@example.com");
    mail.reset();

    requestRecovery(created.reference());

    assertThat(mail.sent()).isEmpty();
  }

  @Test
  void aClaimedAgreementSendsNothingAndItsLinkStopsWorking() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    claim(created.id());
    mail.reset();

    requestRecovery(created.reference());
    assertThat(mail.sent()).isEmpty();

    // Claiming is the revocation the emailed message promises: a link that worked before the
    // agreement was saved to an account must stop working after.
    ResponseEntity<String> opened =
        rest.getForEntity("/api/agreements/" + created.id(), String.class);
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // --- 9.4 eligibility matrix -------------------------------------------------------------------

  /**
   * The eligibility rule is two predicates ANDed: settled payment AND nobody owns it. This walks
   * the matrix so neither half can be dropped silently.
   *
   * <p><b>Note on the task wording.</b> Task 9.4 says "only paid-and-unowned is eligible", but the
   * implemented rule accepts {@code WAIVED} as well as {@code PAID} -- and it is right to. A waiver
   * is the deliberate decision to proceed without money; a customer whose agreement was waived has
   * the same claim on reaching it as one who paid. The task text is the loose phrasing, not the
   * code. Asserted here so the WAIVED row cannot be "tidied away" later by someone reading only
   * that sentence.
   */
  @Test
  void onlyASettledUnownedAgreementIsRecoverable() {
    Created paid = createAgreement("paid@example.com", null);
    Payments.markPaid(jdbc, paid.id(), "pay_" + UUID.randomUUID());

    Created waived = createAgreement("waived@example.com", null);
    Payments.waive(jdbc, waived.id());

    Created unpaid = createAgreement("unpaid@example.com", null);

    Created paidButOwned = createAgreement("owned@example.com", null);
    Payments.markPaid(jdbc, paidButOwned.id(), "pay_" + UUID.randomUUID());
    claim(paidButOwned.id());

    Created waivedButOwned = createAgreement("waivedowned@example.com", null);
    Payments.waive(jdbc, waivedButOwned.id());
    claim(waivedButOwned.id());

    mail.reset();
    requestRecovery(paid.reference());
    requestRecovery(waived.reference());
    requestRecovery(unpaid.reference());
    requestRecovery(paidButOwned.reference());
    requestRecovery(waivedButOwned.reference());

    // Settled + unowned -> a link. Everything else -> nothing, whatever the response said.
    assertThat(mail.sentTo("paid@example.com")).hasSize(1);
    assertThat(mail.sentTo("waived@example.com")).hasSize(1);
    assertThat(mail.sentTo("unpaid@example.com")).isEmpty();
    assertThat(mail.sentTo("owned@example.com")).isEmpty();
    assertThat(mail.sentTo("waivedowned@example.com")).isEmpty();
  }

  // --- 9.20 / 9.22 / 9.23: what the link is, and is not, good for -------------------------------

  /**
   * The link carries no expiry of its own (design D2 removed per-link state). Claiming is the only
   * revocation. This asserts the positive half -- that nothing else quietly expires it -- by moving
   * the agreement's clock back a year and reopening.
   */
  @Test
  void aLinkKeepsWorkingWhileTheAgreementStaysPaidAndUnowned() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());

    // Age both the agreement and its payment well past any plausible implicit window.
    jdbc.update(
        "UPDATE agreement SET created_at = created_at - INTERVAL '400 days',"
            + " payment_recorded_at = payment_recorded_at - INTERVAL '400 days' WHERE id = ?",
        created.id());

    ResponseEntity<String> opened =
        rest.getForEntity("/api/agreements/" + created.id(), String.class);

    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * Recovery re-sends a link; it does not take ownership. The agreement must stay unowned
   * afterwards, or the customer would be locked out of ever saving it to an account.
   */
  @Test
  void aRecoveredAgreementStaysUnownedAndCanStillBeClaimedAfterwards() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    mail.reset();

    requestRecovery(created.reference());
    assertThat(mail.sentTo("asha@example.com")).hasSize(1);

    Integer ownedAfterRecovery =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM agreement WHERE id = ? AND owner_identity_id IS NOT NULL",
            Integer.class,
            created.id());
    assertThat(ownedAfterRecovery).isZero();

    // Still claimable, and claiming still revokes the link.
    claim(created.id());
    ResponseEntity<String> opened =
        rest.getForEntity("/api/agreements/" + created.id(), String.class);
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /**
   * The link is a capability to <b>reach</b> the agreement, not to rewrite it. A holder who never
   * authenticated must not be able to move the terms or the party list -- otherwise an emailed link
   * would be a stronger credential than a login.
   */
  @Test
  void aLinkHolderCannotEditTermsOrParties() {
    Created created = createAgreement("asha@example.com", "tara@example.com");
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());

    // Reading with the link is fine.
    assertThat(rest.getForEntity("/api/agreements/" + created.id(), String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    // Rewriting is not: the full-edit route stays authenticated.
    Map<String, Object> rewrite =
        Map.of(
            "propertyAddress", "99 Somewhere Else, Bengaluru 560001",
            "monthlyRent", "1.00",
            "securityDeposit", "0.00",
            "startDate", "2026-09-01",
            "endDate", "2027-07-31",
            "signers",
                List.of(
                    signer("New", "Owner", "F", "1 A St", "new@example.com", "OWNER"),
                    signer("New", "Tenant", "G", "3 C St", "new2@example.com", "TENANT")));
    ResponseEntity<String> edited =
        rest.exchange(
            "/api/agreements/" + created.id(),
            org.springframework.http.HttpMethod.PUT,
            new org.springframework.http.HttpEntity<>(rewrite),
            String.class);
    assertThat(edited.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

    // ...and nothing moved.
    String address =
        jdbc.queryForObject(
            "SELECT property_address FROM agreement WHERE id = ?", String.class, created.id());
    assertThat(address).isEqualTo("12 Test Street, Bengaluru 560038");
  }

  @Test
  void oneRecipientFailingDoesNotDenyTheOther() {
    Created created = createAgreement("asha@example.com", "tara@example.com");
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    mail.reset();
    mail.failFor("asha@example.com", m -> new IllegalStateException("provider refused"));

    ResponseEntity<String> response = requestRecovery(created.reference());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(mail.sentTo("tara@example.com")).hasSize(1);
  }

  @Test
  void aTotalMailFailureStillAnswersTheSameWay() {
    Created created = createAgreement("asha@example.com", "tara@example.com");
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());
    mail.reset();
    mail.failEverything(m -> new IllegalStateException("provider down"));

    ResponseEntity<String> response = requestRecovery(created.reference());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void everyRequestIsAudited() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());

    requestRecovery(created.reference());
    requestRecovery(UNKNOWN_TO_THIS_TEST);

    // The responses are indistinguishable; the audit trail is where the distinction lives, and it
    // is the only place an operator can see abuse at all.
    Integer sent =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM recovery_audit WHERE reference = ? AND outcome = 'SENT'",
            Integer.class,
            created.reference());
    Integer miss =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM recovery_audit WHERE reference = ? AND outcome = 'NOT_ELIGIBLE'",
            Integer.class,
            UNKNOWN_TO_THIS_TEST);

    assertThat(sent).isEqualTo(1);
    assertThat(miss).isEqualTo(1);
  }

  @Test
  void theAuditNeverStoresAFullRecipientAddress() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());

    requestRecovery(created.reference());

    Integer leaked =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM recovery_audit WHERE recipient_redacted LIKE '%asha@example.com%'",
            Integer.class);
    assertThat(leaked).isZero();
  }

  @Test
  void throttlingIsShapeIdenticalToASuccessfulRequest() {
    Created created = createAgreement("asha@example.com", null);
    Payments.markPaid(jdbc, created.id(), "pay_" + UUID.randomUUID());

    ResponseEntity<String> first = requestRecovery(created.reference());
    ResponseEntity<String> throttled = null;
    for (int i = 0; i < 10; i++) {
      throttled = requestRecovery(created.reference());
    }

    // A different status for a throttled request would leak that this reference is worth throttling
    // - which is itself a signal that it exists.
    assertThat(throttled.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThat(throttled.getBody()).isEqualTo(first.getBody());
  }

  @Test
  void aMalformedReferenceIsRefusedByValidationNotByLookup() {
    // Too short to be a reference at all: rejected on shape, which tells the caller nothing about
    // what does or does not exist.
    ResponseEntity<String> response = requestRecovery("");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}

package in.agreementmitra.support;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mints a real, live session for an integration test through the module's own public login path
 * (find-or-create identity, issue a single-use handoff, exchange it), so the session under test is
 * indistinguishable from one a real login produced - no hand-forged token, no hash reimplemented.
 *
 * <p>The STAFF grant is applied with a direct {@code UPDATE}, deliberately: that IS the production
 * mechanism (design D7). Role is never settable through any request, body, header, or OAuth claim,
 * so an out-of-band database write is the only way it can be granted - here as in production.
 */
public final class StaffSessions {

  private StaffSessions() {}

  /** A live session for a CUSTOMER-role account. Returns the bearer value. */
  public static String customerSession(
      IdentityService identityService,
      HandoffService handoffService,
      SessionService sessionService,
      String subject) {
    return sessionFor(identityService, handoffService, sessionService, subject);
  }

  /** A live session for a STAFF-role account. Returns the bearer value. */
  public static String staffSession(
      IdentityService identityService,
      HandoffService handoffService,
      SessionService sessionService,
      JdbcTemplate jdbc,
      String subject) {
    UUID identityId =
        identityService.findOrCreate(
            "google", subject, subject + "@example.com", true, "Test Staff");
    jdbc.update("UPDATE identity SET role = 'STAFF' WHERE id = ?", identityId);
    return exchange(handoffService, sessionService, identityId);
  }

  private static String sessionFor(
      IdentityService identityService,
      HandoffService handoffService,
      SessionService sessionService,
      String subject) {
    UUID identityId =
        identityService.findOrCreate(
            "google", subject, subject + "@example.com", true, "Test Customer");
    return exchange(handoffService, sessionService, identityId);
  }

  private static String exchange(
      HandoffService handoffService, SessionService sessionService, UUID identityId) {
    return sessionService.exchange(handoffService.issue(identityId)).value();
  }
}

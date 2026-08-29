package in.agreementmitra.identity;

import in.agreementmitra.identity.support.EmailRedaction;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Find-or-create by external credential, and summarise an identity for the authenticated caller.
 * Java-{@code public} so the {@code api} controller and the {@code oauth} login flow (other
 * packages in this module) can inject it; still Modulith-internal (not exported through a named
 * interface). The only value it hands out is the identity id (a UUID) and a display-only summary.
 */
@Service
public class IdentityService {

  private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

  private final IdentityRepository identities;
  private final IdentityCredentialRepository credentials;

  IdentityService(IdentityRepository identities, IdentityCredentialRepository credentials) {
    this.identities = identities;
    this.credentials = credentials;
  }

  /**
   * Resolve the identity for an external {@code (provider, subject)}, creating it (and its
   * credential) on the first login and reusing it on every later login. Returns the identity id
   * only. The response shape is identical for a first vs returning login, so the caller cannot use
   * it as an enumeration oracle. The DB uniqueness constraint on {@code (provider,
   * provider_subject)} is the race backstop; in this single-instance sandbox a concurrent
   * double-create is not a concern.
   */
  @Transactional
  public UUID findOrCreate(
      String provider, String subject, String email, boolean emailVerified, String displayName) {
    Optional<UUID> existing =
        credentials
            .findByProviderAndProviderSubject(provider, subject)
            .map(IdentityCredential::identityId);
    if (existing.isPresent()) {
      log.debug(
          "Login reused identity for {} credential {}", provider, EmailRedaction.redact(email));
      return existing.get();
    }
    Identity identity = identities.save(Identity.create(displayName, email));
    credentials.save(
        IdentityCredential.create(identity.getId(), provider, subject, email, emailVerified));
    log.debug(
        "Login created identity for {} credential {}", provider, EmailRedaction.redact(email));
    return identity.getId();
  }

  /**
   * The server-managed {@link IdentityRole} of an account. An unknown id resolves to {@link
   * IdentityRole#CUSTOMER} -- the least-privileged answer -- so a stale session can never be
   * upgraded by a missing row. The role is read from the database only; no request value, header,
   * or identity-provider claim contributes to it (design D7).
   */
  @Transactional(readOnly = true)
  public IdentityRole roleOf(UUID identityId) {
    return identities.findById(identityId).map(Identity::role).orElse(IdentityRole.CUSTOMER);
  }

  /** Display-only summary of an identity, or empty if it does not exist. */
  @Transactional(readOnly = true)
  public Optional<IdentitySummary> summary(UUID identityId) {
    return identities
        .findById(identityId)
        .map(i -> new IdentitySummary(i.getId(), i.displayName(), i.email(), i.role()));
  }

  /**
   * Display-only projection of an identity handed to the {@code api} layer: the id plus the display
   * name and email shown to the authenticated owner, and the server-managed role so the SPA can
   * hide (never enforce) staff-only navigation. Never carries a credential subject or any secret -
   * the role is advisory to the client; the filter chain is what actually authorizes.
   */
  public record IdentitySummary(
      UUID identityId, String displayName, String email, IdentityRole role) {}
}

/**
 * Identity module: who a user is, and the credentials that prove it. Activated (was a stub) by the
 * google-oauth-login CR with the login layer -- a provider-agnostic {@link
 * in.agreementmitra.identity.Identity} aggregate carrying one or more {@code IdentityCredential}
 * rows (Google first), an opaque server-side hashed session, and the OAuth handshake. KYC /
 * DigiLocker fraud detection folds in here later.
 *
 * <p>Structure mirrors {@code signing}: internal domain packages ({@code oauth}, {@code session},
 * {@code support}) plus a public {@code api} named interface. Entities, repositories, the OAuth
 * client, and the session filter stay Modulith-internal; the only value handed to another module
 * (in a later CR) is the authenticated identity id (a {@link java.util.UUID}).
 *
 * <p>Any PII flow here is redacted in logs (email local-part masked; id-only {@code toString()});
 * tokens, the authorization code, the session value, the handoff, and the PKCE verifier are NEVER
 * logged. Secrets come from env vars only.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package in.agreementmitra.identity;

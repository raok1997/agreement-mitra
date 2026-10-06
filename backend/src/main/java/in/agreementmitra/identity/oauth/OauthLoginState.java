package in.agreementmitra.identity.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * The single-use, short-lived server state for one in-flight Google login. Stores only the SHA-256
 * hash of the random OAuth {@code state} (never the raw value) plus the PKCE {@code code_verifier}
 * replayed at token exchange, and the hash of the login-binding nonce held in the starting
 * browser's cookie (login-browser-binding D2). Consumed atomically at callback ({@code consumedAt}
 * set); a reused or unknown state resolves to no row and the callback is refused.
 *
 * <p>The {@code code_verifier} is a short-lived per-login secret (deleted-by-consume;
 * encrypt-at-rest is a flagged, not-required sandbox follow-up). {@code toString()} is id-only.
 */
@Entity
@Table(name = "oauth_login_state")
class OauthLoginState implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "state_hash", nullable = false)
  private String stateHash;

  @Column(name = "code_verifier", nullable = false)
  private String codeVerifier;

  @Column(name = "redirect_uri")
  private String redirectUri;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "browser_binding_hash")
  private String browserBindingHash;

  @Transient private boolean isNew = true;

  protected OauthLoginState() {
    // JPA
  }

  private OauthLoginState(
      UUID id,
      String stateHash,
      String codeVerifier,
      String redirectUri,
      Instant createdAt,
      Instant expiresAt,
      String browserBindingHash) {
    this.id = id;
    this.stateHash = stateHash;
    this.codeVerifier = codeVerifier;
    this.redirectUri = redirectUri;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.browserBindingHash = browserBindingHash;
  }

  static OauthLoginState create(
      String stateHash,
      String codeVerifier,
      String redirectUri,
      Instant expiresAt,
      String browserBindingHash) {
    return new OauthLoginState(
        UUID.randomUUID(),
        stateHash,
        codeVerifier,
        redirectUri,
        Instant.now(),
        expiresAt,
        browserBindingHash);
  }

  @Override
  public UUID getId() {
    return id;
  }

  String codeVerifier() {
    return codeVerifier;
  }

  String redirectUri() {
    return redirectUri;
  }

  String browserBindingHash() {
    return browserBindingHash;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostPersist
  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof OauthLoginState other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- never the state hash, binding hash or PKCE verifier.
    return "OauthLoginState{id=" + id + "}";
  }
}

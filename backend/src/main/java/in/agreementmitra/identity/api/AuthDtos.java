package in.agreementmitra.identity.api;

/**
 * Request/response records for the auth endpoints. Grouped in one file since each is a tiny DTO of
 * the identity module's public {@code api} surface. No secret or token ever appears here: the
 * session value travels only in the HttpOnly cookie, and the summary carries only display fields.
 */
public final class AuthDtos {

  private AuthDtos() {}

  /** Body of {@code POST /api/auth/session/exchange}: the single-use handoff to exchange. */
  public record SessionExchangeRequest(String handoff) {}

  /**
   * The identity summary shown to the authenticated caller (display fields only), plus the
   * server-managed {@code role}. The role is advisory: it lets the SPA hide a staff-only screen
   * rather than dangle a link that 403s. It authorizes nothing - every staff route is gated
   * server-side in the security filter chain, so a client that lies about its role gains nothing.
   */
  public record MeResponse(String identityId, String displayName, String email, String role) {}

  /**
   * Response of a successful exchange: the caller's summary only. The session value is set as the
   * HttpOnly cookie and never appears in a body.
   */
  public record SessionResponse(MeResponse me) {}
}

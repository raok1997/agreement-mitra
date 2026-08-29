// Auth API calls for the optional Google login. Kept here with the other API modules; the reactive
// session lives in authStore.ts. Anonymous drafting uses none of this.

const BASE = "/api";

/** The account's server-managed role. Advisory to the client; the backend is what authorizes. */
export type IdentityRole = "CUSTOMER" | "STAFF";

/**
 * The authenticated caller's identity summary (display fields only -- no token, no subject), plus
 * the server-managed role.
 *
 * The role exists so the SPA can hide a staff-only screen rather than dangle a link that 403s. It
 * grants nothing: every staff route is gated server-side, so a tampered client gains no access.
 */
export interface MeView {
  identityId: string;
  displayName: string | null;
  email: string | null;
  role: IdentityRole;
}

/** Response of a successful session exchange: the opaque session value (once) plus the summary. */
export interface SessionView {
  session: string;
  me: MeView;
}

/** An Error carrying the HTTP status, so callers can special-case 401 (clear the session). */
export class AuthHttpError extends Error {
  constructor(public readonly status: number) {
    super(`Auth request failed: ${status}`);
    this.name = "AuthHttpError";
  }
}

/**
 * The backend URL that begins the Google login handshake. Navigating the browser here 302s to
 * Google's consent screen; the backend drives the rest and 302s back to the SPA callback route.
 */
export function googleStartUrl(): string {
  return `${BASE}/auth/google/start`;
}

/** Exchange the single-use handoff (from the callback URL) for an opaque session. */
export async function exchangeHandoff(handoff: string): Promise<SessionView> {
  const res = await fetch(`${BASE}/auth/session/exchange`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ handoff }),
  });
  if (!res.ok) throw new AuthHttpError(res.status);
  return res.json();
}

/** Fetch the current identity summary for a session value. Throws AuthHttpError(401) if invalid. */
export async function fetchMe(session: string): Promise<MeView> {
  const res = await fetch(`${BASE}/auth/me`, {
    headers: { Authorization: `Bearer ${session}` },
  });
  if (!res.ok) throw new AuthHttpError(res.status);
  return res.json();
}

/** Revoke the session server-side. Best-effort; the caller clears local state regardless. */
export async function logout(session: string): Promise<void> {
  await fetch(`${BASE}/auth/logout`, {
    method: "POST",
    headers: { Authorization: `Bearer ${session}` },
  });
}

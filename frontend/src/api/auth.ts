// Auth API calls for the optional Google login. Kept here with the other API modules; the reactive
// sign-in state lives in authStore.ts. Anonymous drafting uses none of this. The session itself is an
// HttpOnly cookie the browser sends on its own -- no call here ever sees or sends its value.

import { apiFetch } from "./http";

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

/** Response of a successful session exchange: the summary only (the session is set as a cookie). */
export interface SessionView {
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

/** Exchange the single-use handoff (from the callback URL); the server sets the session cookie. */
export async function exchangeHandoff(handoff: string): Promise<SessionView> {
  const res = await apiFetch(`${BASE}/auth/session/exchange`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ handoff }),
  });
  if (!res.ok) throw new AuthHttpError(res.status);
  return res.json();
}

/** Fetch the identity summary for the browser's session cookie. Throws AuthHttpError if none. */
export async function fetchMe(): Promise<MeView> {
  const res = await apiFetch(`${BASE}/auth/me`);
  if (!res.ok) throw new AuthHttpError(res.status);
  return res.json();
}

/**
 * Revoke the session and expire its cookie server-side (only the server can clear an HttpOnly
 * cookie). Throws on anything but 204, so the caller can keep showing the user as signed in.
 */
export async function logout(): Promise<void> {
  const res = await apiFetch(`${BASE}/auth/logout`, { method: "POST" });
  if (res.status !== 204) throw new AuthHttpError(res.status);
}

// Reactive auth store: holds the opaque session value in memory (never persisted -- a page reload
// logs out, acceptable for the sandbox) and the current identity summary. Attaches the Bearer header
// to authenticated calls and clears on logout or any 401. Anonymous drafting never touches this.

import { reactive, readonly } from "vue";
import {
  AuthHttpError,
  exchangeHandoff,
  fetchMe,
  logout as logoutApi,
  type MeView,
} from "./auth";

interface AuthState {
  session: string | null;
  me: MeView | null;
}

const state = reactive<AuthState>({ session: null, me: null });

/** Authorization header for an authenticated call, or empty when signed out. */
export function authHeader(): Record<string, string> {
  return state.session ? { Authorization: `Bearer ${state.session}` } : {};
}

export function isAuthenticated(): boolean {
  return state.session !== null;
}

/** Exchange a handoff (from the callback URL) for a session and remember it + the identity. */
export async function completeLogin(handoff: string): Promise<void> {
  const { session, me } = await exchangeHandoff(handoff);
  state.session = session;
  state.me = me;
}

/** Re-fetch the identity for the held session; a 401 clears the session (it is no longer valid). */
export async function refreshMe(): Promise<void> {
  if (!state.session) return;
  try {
    state.me = await fetchMe(state.session);
  } catch (e) {
    if (e instanceof AuthHttpError && e.status === 401) clear();
    else throw e;
  }
}

/** Revoke server-side (best-effort) and clear local state. Always ends signed out. */
export async function logout(): Promise<void> {
  const session = state.session;
  clear();
  if (session) {
    try {
      await logoutApi(session);
    } catch {
      // Already cleared locally; a failed revoke does not resurrect the session.
    }
  }
}

function clear(): void {
  state.session = null;
  state.me = null;
}

/** Read-only view for components. */
export const auth = readonly(state);

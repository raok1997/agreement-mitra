// Reactive sign-in state (cookie-session-auth D8). The session itself is an HttpOnly cookie that no
// script can read, so "signed in" is whatever GET /api/auth/me says on boot -- the store holds only
// the identity summary, never a session value. Anonymous drafting never touches this.
//
// Staleness rule (asymmetric, on purpose): completeLogin and logout are authoritative -- on success
// they apply their result unconditionally and bump `generation`. init and reconcile capture
// `generation` before sending /me and discard their answer if it moved, so a boot /me sent with the
// pre-login cookie cannot overwrite a login that completed while it was in flight.

import { computed, reactive, readonly } from "vue";
import {
  AuthHttpError,
  exchangeHandoff,
  fetchMe,
  logout as logoutApi,
  type MeView,
} from "./auth";
import { setReconcileHook } from "./http";

interface AuthState {
  me: MeView | null;
  ready: boolean;
}

/** The pre-cookie stopgap kept the session value here; it is purged and never sent. */
const LEGACY_SESSION_KEY = "am.session";

const state = reactive<AuthState>({ me: null, ready: false });

let generation = 0;
let initStarted = false;
let settledByLogin = false;
let reconciling: Promise<void> | null = null;

let resolveReady!: () => void;
const readyPromise = new Promise<void>((resolve) => {
  resolveReady = resolve;
});

function markReady(): void {
  state.ready = true;
  resolveReady();
}

/** Resolves once the first sign-in decision is known (boot /me or a completed login). */
export function whenReady(): Promise<void> {
  return readyPromise;
}

export const isSignedIn = computed(() => state.me !== null);

function purgeLegacySession(): void {
  try {
    window.sessionStorage.removeItem(LEGACY_SESSION_KEY);
  } catch {
    // Storage blocked: there is nothing stored to purge either.
  }
}

/** Boot: purge the legacy stored value, ask /me once, resolve `ready`. Runs once per page. */
export async function init(): Promise<void> {
  if (initStarted) return;
  initStarted = true;
  purgeLegacySession();
  if (settledByLogin) {
    markReady();
    return;
  }
  const captured = generation;
  let me: MeView | null;
  try {
    me = await fetchMe();
  } catch {
    me = null;
  }
  if (captured === generation) state.me = me;
  markReady();
}

/** Exchange a handoff (from the callback URL); the server sets the cookie, we keep the summary. */
export async function completeLogin(handoff: string): Promise<void> {
  const { me } = await exchangeHandoff(handoff);
  generation++;
  settledByLogin = true;
  state.me = me;
  markReady();
}

/**
 * Sign out server-side first -- only the server can expire the HttpOnly cookie. On any failure the
 * user is still shown as signed in and the error propagates: showing signed-out while the cookie
 * lives would let the next person on a shared browser reload into this account.
 */
export async function logout(): Promise<void> {
  await logoutApi();
  generation++;
  state.me = null;
}

/**
 * Re-check /me after an API call was refused, while signed in. De-duplicated. A 401/403 flips the
 * state to signed out (the session expired or was revoked elsewhere); a network error keeps it.
 */
export function reconcile(): Promise<void> {
  if (state.me === null) return Promise.resolve();
  if (reconciling) return reconciling;
  const captured = generation;
  reconciling = (async () => {
    try {
      const me = await fetchMe();
      if (captured === generation) state.me = me;
    } catch (e) {
      // Only "no session" signs out; a 5xx or a network error keeps the current state.
      if (
        e instanceof AuthHttpError &&
        (e.status === 401 || e.status === 403) &&
        captured === generation
      )
        state.me = null;
    } finally {
      reconciling = null;
    }
  })();
  return reconciling;
}

setReconcileHook(() => {
  void reconcile();
});

/** Read-only view for components. */
export const auth = readonly(state);

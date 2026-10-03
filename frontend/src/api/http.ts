// The one fetch wrapper every src/api module goes through (cookie-session-auth D8). The session is
// an HttpOnly cookie the browser attaches by itself; this wrapper adds the double-submit CSRF header
// to unsafe methods, retries exactly once on a CSRF refusal, and asks the auth store to re-check
// the session after an authorization failure. A guard test fails the build if any other module
// calls fetch directly.

import { readCookie } from "./cookies";

const SECURE_CSRF_COOKIE = "__Host-XSRF-TOKEN";
const INSECURE_CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_PROBLEM_TYPE = "urn:agreementmitra:problem:csrf";
const CSRF_BOOTSTRAP = "/api/auth/csrf";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

let bootstrap: Promise<void> | null = null;
let reconcileHook: (() => void) | null = null;

/** Registered by the auth store: re-check /me after a 401 or a non-CSRF 403. */
export function setReconcileHook(hook: (() => void) | null): void {
  reconcileHook = hook;
}

/** The CSRF token, preferring the __Host- cookie over a (possibly planted) unprefixed one. */
function csrfToken(): string | null {
  return readCookie(SECURE_CSRF_COOKIE) ?? readCookie(INSECURE_CSRF_COOKIE);
}

/** Obtain a CSRF cookie when neither exists. Concurrent callers share one bootstrap request. */
export function ensureCsrf(): Promise<void> {
  if (csrfToken() !== null) return Promise.resolve();
  if (!bootstrap) {
    bootstrap = fetch(CSRF_BOOTSTRAP, { credentials: "same-origin" })
      .then(
        () => undefined,
        () => undefined,
      )
      .finally(() => {
        bootstrap = null;
      });
  }
  return bootstrap;
}

function pathOf(input: string): string {
  try {
    return new URL(input, "http://localhost").pathname;
  } catch {
    return input;
  }
}

async function isCsrfRefusal(res: Response): Promise<boolean> {
  if (res.status !== 403 || typeof res.clone !== "function") return false;
  try {
    const body = await res.clone().json();
    return body?.type === CSRF_PROBLEM_TYPE;
  } catch {
    return false;
  }
}

function withCsrfHeader(init: RequestInit): RequestInit {
  const token = csrfToken();
  if (token === null) return init;
  if (init.headers instanceof Headers) {
    const headers = new Headers(init.headers);
    headers.set(CSRF_HEADER, token);
    return { ...init, headers };
  }
  const base = Array.isArray(init.headers)
    ? Object.fromEntries(init.headers)
    : (init.headers as Record<string, string> | undefined);
  return { ...init, headers: { ...base, [CSRF_HEADER]: token } };
}

export async function apiFetch(
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const unsafe = !SAFE_METHODS.has(method);
  const base: RequestInit = { ...init, credentials: "same-origin" };

  let res: Response;
  let csrfRefused = false;
  if (unsafe) {
    await ensureCsrf();
    res = await fetch(input, withCsrfHeader(base));
    if (await isCsrfRefusal(res)) {
      // CsrfFilter refused before any handler ran, so resending cannot double-apply anything. The
      // refusal itself normally carries a fresh cookie; bootstrap only if there is still none.
      await ensureCsrf();
      res = await fetch(input, withCsrfHeader(base));
      csrfRefused = await isCsrfRefusal(res);
    }
  } else {
    res = await fetch(input, base);
  }

  const authFailure =
    res.status === 401 || (res.status === 403 && !csrfRefused);
  if (authFailure && !pathOf(input).startsWith("/api/auth/") && reconcileHook) {
    reconcileHook();
  }
  return res;
}

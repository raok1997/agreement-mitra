import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// The store drives sign-in state from GET /api/auth/me -- the session is an HttpOnly cookie no
// script can read. These tests run the real store (fresh module per test: it is a singleton with a
// module-load `ready` promise) against a stubbed fetch at the network boundary, and pin the
// staleness rule: completeLogin/logout are authoritative, init/reconcile discard stale /me answers.

vi.mock("./cookies", () => ({
  readCookie: (name: string) =>
    name === "__Host-XSRF-TOKEN" ? "csrf-token" : null,
}));

const ME = {
  identityId: "id-1",
  displayName: "Asha",
  email: "asha@example.com",
  role: "CUSTOMER",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
function empty(status: number): Response {
  return new Response(null, { status });
}

/** A controllable pending response. */
function deferred(): {
  promise: Promise<Response>;
  resolve: (r: Response) => void;
} {
  let resolve!: (r: Response) => void;
  const promise = new Promise<Response>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

type Store = typeof import("./authStore");
async function freshStore(): Promise<Store> {
  vi.resetModules();
  return import("./authStore");
}

function urlOf(call: unknown[]): string {
  return String(call[0]);
}

describe("authStore", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("init with /me 200 is signed in and ready", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(200, ME)));
    const store = await freshStore();

    await store.init();

    expect(store.isSignedIn.value).toBe(true);
    expect(store.auth.me?.email).toBe("asha@example.com");
    expect(store.auth.ready).toBe(true);
    await expect(store.whenReady()).resolves.toBeUndefined();
  });

  it("init with /me 403 is signed out and ready", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(empty(403)));
    const store = await freshStore();

    await store.init();

    expect(store.isSignedIn.value).toBe(false);
    expect(store.auth.ready).toBe(true);
  });

  it("init purges the legacy am.session value and never sends it", async () => {
    sessionStorage.setItem("am.session", "legacy-session-value");
    const fetchMock = vi.fn().mockResolvedValue(empty(403));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();

    await store.init();

    expect(sessionStorage.getItem("am.session")).toBeNull();
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain(
      "legacy-session-value",
    );
  });

  it("completeLogin sets me and stores no session value anywhere", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(200, { me: ME })));
    const store = await freshStore();

    await store.completeLogin("handoff-1");

    expect(store.auth.me?.identityId).toBe("id-1");
    expect(store.auth.ready).toBe(true);
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.length).toBe(0);
    expect(JSON.stringify(store.auth)).not.toContain("session");
  });

  it("logout posts and clears on 204", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, { me: ME }))
      .mockResolvedValueOnce(empty(204));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.completeLogin("h");

    await store.logout();

    expect(store.isSignedIn.value).toBe(false);
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe("/api/auth/logout");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "csrf-token" });
  });

  it.each([
    ["a network error", () => Promise.reject(new TypeError("offline"))],
    ["a 500", () => Promise.resolve(empty(500))],
  ])("logout on %s keeps me and reports the failure", async (_, failure) => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, { me: ME }))
      .mockImplementationOnce(failure);
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.completeLogin("h");

    await expect(store.logout()).rejects.toBeDefined();

    expect(store.isSignedIn.value).toBe(true);
  });

  it("reconcile after a revoked session flips to signed out", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, ME))
      .mockResolvedValueOnce(empty(403));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.init();
    expect(store.isSignedIn.value).toBe(true);

    await store.reconcile();

    expect(store.isSignedIn.value).toBe(false);
  });

  it("an authorization failure elsewhere triggers the reconcile through the fetch wrapper", async () => {
    const fetchMock = vi.fn((input: string) => {
      if (input === "/api/agreements") return Promise.resolve(empty(401));
      return Promise.resolve(empty(401)); // /me: the session is gone
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json(200, ME)));
    const store = await freshStore();
    await store.init();
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await import("./http");

    await apiFetch("/api/agreements");
    await vi.waitFor(() => expect(store.isSignedIn.value).toBe(false));
    expect(fetchMock.mock.calls.map(urlOf)).toContain("/api/auth/me");
  });

  it("reconcile on a 5xx keeps the state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, ME))
      .mockResolvedValueOnce(empty(503));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.init();

    await store.reconcile();

    expect(store.isSignedIn.value).toBe(true);
  });

  it("reconcile on a network error keeps the state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, ME))
      .mockRejectedValueOnce(new TypeError("offline"));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.init();

    await store.reconcile();

    expect(store.isSignedIn.value).toBe(true);
  });

  it("concurrent reconciles share one /me call", async () => {
    const me = deferred();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, ME))
      .mockReturnValueOnce(me.promise);
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.init();

    const a = store.reconcile();
    const b = store.reconcile();
    me.resolve(json(200, ME));
    await Promise.all([a, b]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("the real mount order: a login completing during the boot /me stays signed in (exchange first)", async () => {
    const exchange = deferred();
    const bootMe = deferred();
    const fetchMock = vi.fn((input: string) =>
      input === "/api/auth/session/exchange"
        ? exchange.promise
        : bootMe.promise,
    );
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();

    // AuthCallback (child) mounts first and starts the exchange, then App runs init().
    const login = store.completeLogin("h");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const boot = store.init();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    exchange.resolve(json(200, { me: ME }));
    await login;
    bootMe.resolve(empty(403)); // sent with the pre-login cookie
    await boot;

    expect(store.isSignedIn.value).toBe(true);
  });

  it("the real mount order: a login completing during the boot /me stays signed in (/me first)", async () => {
    const exchange = deferred();
    const bootMe = deferred();
    const fetchMock = vi.fn((input: string) =>
      input === "/api/auth/session/exchange"
        ? exchange.promise
        : bootMe.promise,
    );
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();

    const login = store.completeLogin("h");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const boot = store.init();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    bootMe.resolve(empty(403));
    await boot;
    exchange.resolve(json(200, { me: ME }));
    await login;

    expect(store.isSignedIn.value).toBe(true);
  });

  it("init after a settled login makes no /me call and resolves ready", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, { me: ME }));
    vi.stubGlobal("fetch", fetchMock);
    const store = await freshStore();
    await store.completeLogin("h");

    await store.init();

    expect(fetchMock.mock.calls.map(urlOf)).not.toContain("/api/auth/me");
    expect(store.auth.ready).toBe(true);
    expect(store.isSignedIn.value).toBe(true);
  });
});

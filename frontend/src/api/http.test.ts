import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// The fetch wrapper at the network boundary: CSRF header on unsafe methods only, a shared
// bootstrap when no CSRF cookie exists, exactly one retry on the CSRF problem type (never on a bare
// 403), and the reconcile hook after an authorization failure outside /api/auth/*.

const cookies = vi.hoisted(() => ({ jar: {} as Record<string, string> }));
vi.mock("./cookies", () => ({
  readCookie: (name: string) => cookies.jar[name] ?? null,
}));

const CSRF_TYPE = "urn:agreementmitra:problem:csrf";

function ok(): Response {
  return new Response("{}", { status: 200 });
}
function csrfRefusal(): Response {
  return new Response(JSON.stringify({ type: CSRF_TYPE, status: 403 }), {
    status: 403,
    headers: { "Content-Type": "application/problem+json" },
  });
}
function status(code: number): Response {
  return new Response(null, { status: code });
}

type Http = typeof import("./http");
async function freshHttp(): Promise<Http> {
  vi.resetModules();
  return import("./http");
}

function headerOf(call: unknown[]): string | undefined {
  const init = call[1] as RequestInit | undefined;
  return (init?.headers as Record<string, string> | undefined)?.[
    "X-XSRF-TOKEN"
  ];
}

describe("apiFetch", () => {
  beforeEach(() => {
    cookies.jar = {};
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET sends no CSRF header and never bootstraps", async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok());
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await apiFetch("/api/templates");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]).toEqual({ credentials: "same-origin" });
  });

  it("POST with the cookie sets the header and does not bootstrap", async () => {
    cookies.jar["__Host-XSRF-TOKEN"] = "tok";
    const fetchMock = vi.fn().mockResolvedValue(ok());
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await apiFetch("/api/agreements", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "tok" },
    });
  });

  it("POST without the cookie bootstraps once, and concurrent POSTs share it", async () => {
    const fetchMock = vi.fn((input: string) => {
      if (input === "/api/auth/csrf") {
        cookies.jar["__Host-XSRF-TOKEN"] = "booted";
        return Promise.resolve(status(204));
      }
      return Promise.resolve(ok());
    });
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await Promise.all([
      apiFetch("/api/agreements", { method: "POST" }),
      apiFetch("/api/agreements/recovery", { method: "POST" }),
    ]);

    const urls = fetchMock.mock.calls.map((c) => c[0]);
    expect(urls.filter((u) => u === "/api/auth/csrf")).toHaveLength(1);
    const posts = fetchMock.mock.calls.filter((c) => c[0] !== "/api/auth/csrf");
    expect(posts.map(headerOf)).toEqual(["booted", "booted"]);
  });

  it("prefers the __Host- cookie over an unprefixed one", async () => {
    cookies.jar["__Host-XSRF-TOKEN"] = "host";
    cookies.jar["XSRF-TOKEN"] = "planted";
    const fetchMock = vi.fn().mockResolvedValue(ok());
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await apiFetch("/api/agreements", { method: "POST" });

    expect(headerOf(fetchMock.mock.calls[0])).toBe("host");
  });

  it("falls back to the unprefixed cookie in insecure mode", async () => {
    cookies.jar["XSRF-TOKEN"] = "plain";
    const fetchMock = vi.fn().mockResolvedValue(ok());
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await apiFetch("/api/agreements", { method: "POST" });

    expect(headerOf(fetchMock.mock.calls[0])).toBe("plain");
  });

  it("retries exactly once on a CSRF refusal, using the re-read cookie", async () => {
    cookies.jar["XSRF-TOKEN"] = "planted";
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => {
        // The refusal sets the real __Host- cookie.
        cookies.jar["__Host-XSRF-TOKEN"] = "fresh";
        return Promise.resolve(csrfRefusal());
      })
      .mockResolvedValueOnce(ok());
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    const res = await apiFetch("/api/agreements", { method: "POST" });

    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls.map(headerOf)).toEqual(["planted", "fresh"]);
  });

  it("never retries more than once", async () => {
    cookies.jar["__Host-XSRF-TOKEN"] = "tok";
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(csrfRefusal()));
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    const res = await apiFetch("/api/agreements", { method: "POST" });

    expect(res.status).toBe(403);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("never retries a bare 403", async () => {
    cookies.jar["__Host-XSRF-TOKEN"] = "tok";
    const fetchMock = vi.fn().mockResolvedValue(status(403));
    vi.stubGlobal("fetch", fetchMock);
    const { apiFetch } = await freshHttp();

    await apiFetch("/api/agreements/a1/payment/order", { method: "POST" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([401, 403])(
    "a %i outside /api/auth/* invokes the reconcile hook",
    async (code) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(code)));
      const { apiFetch, setReconcileHook } = await freshHttp();
      const hook = vi.fn();
      setReconcileHook(hook);

      await apiFetch("/api/agreements");

      expect(hook).toHaveBeenCalledOnce();
    },
  );

  it("a CSRF refusal that survives the retry does not invoke the hook", async () => {
    cookies.jar["__Host-XSRF-TOKEN"] = "tok";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => Promise.resolve(csrfRefusal())),
    );
    const { apiFetch, setReconcileHook } = await freshHttp();
    const hook = vi.fn();
    setReconcileHook(hook);

    await apiFetch("/api/agreements", { method: "POST" });

    expect(hook).not.toHaveBeenCalled();
  });

  it.each([
    ["GET", "/api/auth/me"],
    ["POST", "/api/auth/session/exchange"],
    ["POST", "/api/auth/logout"],
  ])("%s %s does not invoke the hook", async (method, path) => {
    cookies.jar["__Host-XSRF-TOKEN"] = "tok";
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(401)));
    const { apiFetch, setReconcileHook } = await freshHttp();
    const hook = vi.fn();
    setReconcileHook(hook);

    await apiFetch(path, { method });

    expect(hook).not.toHaveBeenCalled();
  });
});

describe("apiFetch load refusals", () => {
  beforeEach(() => {
    cookies.jar = { "__Host-XSRF-TOKEN": "tok" };
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function problem(code: number, type: string, retryAfter?: string): Response {
    const headers: Record<string, string> = {
      "Content-Type": "application/problem+json",
    };
    if (retryAfter !== undefined) headers["Retry-After"] = retryAfter;
    return new Response(JSON.stringify({ type, status: code }), {
      status: code,
      headers,
    });
  }

  it("throws ServiceBusyError carrying Retry-After for a 429", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          problem(429, "urn:agreementmitra:problem:rate-limited", "42"),
        ),
    );
    const { apiFetch, ServiceBusyError } = await freshHttp();

    const error = await apiFetch("/api/agreements", { method: "POST" }).catch(
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(ServiceBusyError);
    expect((error as InstanceType<typeof ServiceBusyError>).retryAfterSeconds).toBe(42);
    expect((error as Error).message).toMatch(/try again in 42 seconds/);
  });

  it("throws ServiceBusyError for a render-busy 503, defaulting Retry-After when absent", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(problem(503, "urn:agreementmitra:problem:render-busy")),
    );
    const { apiFetch, ServiceBusyError } = await freshHttp();

    const error = await apiFetch("/api/templates/document/preview", {
      method: "POST",
    }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ServiceBusyError);
    expect((error as InstanceType<typeof ServiceBusyError>).retryAfterSeconds).toBe(5);
  });

  it("returns a stamp-render-unavailable 503 and a bodyless 503 unchanged", async () => {
    const stamp = problem(503, "urn:agreementmitra:problem:stamp-render-unavailable");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(stamp).mockResolvedValueOnce(status(503)),
    );
    const { apiFetch } = await freshHttp();

    expect(await apiFetch("/api/staff/estamp", { method: "POST" })).toBe(stamp);
    expect((await apiFetch("/api/agreements/x")).status).toBe(503);
  });

  it("does not retry a refusal and does not treat it as a session problem", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(problem(429, "urn:agreementmitra:problem:rate-limited", "3"));
    vi.stubGlobal("fetch", fetchMock);
    const http = await freshHttp();
    const hook = vi.fn();
    http.setReconcileHook(hook);

    await expect(http.apiFetch("/api/agreements/x")).rejects.toBeInstanceOf(
      http.ServiceBusyError,
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(hook).not.toHaveBeenCalled();
  });
});


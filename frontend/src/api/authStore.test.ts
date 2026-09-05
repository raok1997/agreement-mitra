import { afterEach, describe, expect, it, vi } from "vitest";
import {
  authHeader,
  completeLogin,
  isAuthenticated,
  logout,
  refreshMe,
} from "./authStore";

// The store holds the opaque session in memory and attaches it as a Bearer header. These tests drive
// the real store against a stubbed fetch, asserting the two invariants the CR cares about: a Bearer
// header is attached only when signed in, and any 401 clears the session.

function okJson(body: unknown) {
  return { ok: true, status: 200, json: () => Promise.resolve(body) };
}
function status(code: number) {
  return { ok: false, status: code, json: () => Promise.resolve({}) };
}

describe("authStore", () => {
  afterEach(async () => {
    vi.unstubAllGlobals();
    // Ensure each test starts signed out (logout clears local state regardless of the network).
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okJson({})));
    await logout();
    vi.unstubAllGlobals();
  });

  it("attaches no Authorization header while signed out (anonymous drafting path)", () => {
    expect(isAuthenticated()).toBe(false);
    expect(authHeader()).toEqual({});
  });

  it("exchanges a handoff and then attaches the Bearer header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({
        session: "opaque-123",
        me: {
          identityId: "id-1",
          displayName: "Asha",
          email: "a***@x",
          role: "CUSTOMER",
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await completeLogin("handoff-abc");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/session/exchange",
      expect.objectContaining({ method: "POST" }),
    );
    expect(isAuthenticated()).toBe(true);
    expect(authHeader()).toEqual({ Authorization: "Bearer opaque-123" });
  });

  it("clears the session when refreshMe sees a 401", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        okJson({
          session: "opaque-xyz",
          me: {
            identityId: "id-2",
            displayName: null,
            email: null,
            role: "CUSTOMER",
          },
        }),
      ),
    );
    await completeLogin("handoff-2");
    expect(isAuthenticated()).toBe(true);

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(status(401)));
    await refreshMe();

    expect(isAuthenticated()).toBe(false);
    expect(authHeader()).toEqual({});
  });

  it("logout clears the session and calls the revoke endpoint", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        okJson({
          session: "opaque-9",
          me: {
            identityId: "id-9",
            displayName: null,
            email: null,
            role: "CUSTOMER",
          },
        }),
      ),
    );
    await completeLogin("handoff-9");
    expect(isAuthenticated()).toBe(true);

    const fetchMock = vi.fn().mockResolvedValue(okJson({}));
    vi.stubGlobal("fetch", fetchMock);
    await logout();

    expect(isAuthenticated()).toBe(false);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/logout",
      expect.objectContaining({ method: "POST" }),
    );
  });
});

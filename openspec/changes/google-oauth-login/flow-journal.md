# Flow Journal -- Google Login + Session (CR-A)

> **Purpose.** Development-kickoff handoff for the **login layer** -- the first of two CRs for optional
> Google login. It walks the login journey, freezes the contracts CR-B depends on, and lists watch-outs.
> Rationale is in `design.md` (D1..D8); normative behavior is in the `specs/` deltas.
>
> **Scope boundary.** This CR ends at "an authenticated identity + session exist." Ownership, Save,
> resume, list, and edit are **CR-B (`agreement-ownership`)** and are deliberately absent here.

Locked decisions: **provider-agnostic identity** (Google is the first credential; mobile-OTP can join the
same identity later) and **optional login** (no existing agreement route changes).

---

## 1. The login journey

```
  User                         Frontend (Vue)                  Backend (identity)
   | Land                       | Landing: "Create agreement"   |
   |                            |          "Sign in with Google" |
   | click Sign in ------------>| GET /api/auth/google/start --->| 302 -> Google consent
   |   (consent at Google)      |                               |   (state + PKCE stored, hashed)
   |                            | 302 /auth/callback?handoff --->| callback: validate ID token,
   |                            |                               |   find-or-create identity,
   |                            | POST /auth/session/exchange -->|   mint single-use handoff,
   |                            |   { sessionValue, me } <-------|   mint session (hash stored)
   |                            | (store session in memory)     |
   | use the app -------------->| Authorization: Bearer <sess> ->| SessionAuthFilter: hash + lookup
   | GET /api/auth/me ---------->|                              ->|   -> identity summary
   | Logout ------------------->| POST /api/auth/logout -------->| revoke session -> 204
```

**Invariants:**
- The SPA never sees a Google token -- only a one-time `handoff` in the callback URL and, once, the opaque
  `sessionValue` from exchange (D2, D5).
- The durable session is **minted at exchange**, stored **only as a hash** (D4); the handoff carries no
  session material and is single-use.
- The ID token is fully validated (sig/iss/aud/exp/`email_verified`) **before** any identity exists (D2).
- Anonymous drafting is **unchanged** -- login is optional and additive.

The full provider dance is in `proposal.md` ("Google OAuth login sequence").

---

## 2. Frozen contracts (what CR-B and the frontend build on)

**Auth endpoints (identity.api):**
- `GET  /api/auth/google/start`               -> `302` to Google
- `GET  /api/auth/google/callback?code&state` -> `302` to SPA `/auth/callback?handoff=<once>`
- `POST /api/auth/session/exchange`           -> `{ handoff }` => `200 { sessionValue, me }`
- `GET  /api/auth/me`            (auth)        -> `200 { identityId, email, displayName }`
- `POST /api/auth/logout`        (auth)        -> `204`

**The principal:** an authenticated request carries the **identity id (a UUID)** as its Spring Security
principal. This is the single value CR-B consumes to own/filter agreements -- no `identity` type crosses
the module boundary (D1).

**Schema (V11):** `identity`, `identity_credential (unique provider+subject)`, `auth_session (unique
value_hash)`, `oauth_login_state`, `login_handoff`. Forward-only; never edit V1..V10; **`agreement` is
untouched** (CR-B's V12 adds `owner_identity_id`).

**Secrets/env:** `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, redirect URI, session + handoff
TTLs, hash pepper -- env only; missing secret fails fast (D2, D6, D7).

---

## 3. Independently-startable slices

1. **Schema + module activation** (`tasks.md` 1, 2.1-2.2) -- V11 + `identity` skeleton. Unblocks the rest.
2. **Login + session** (`tasks.md` 2.3-2.10, 3) -- OAuth handshake, ID-token validation, opaque session,
   filter, SecurityConfig. Testable with a **stubbed Google** (mock token endpoint + JWKS).
3. **Frontend** (`tasks.md` 4) -- landing, Sign-in, `/auth/callback`, auth store. Depends on the frozen
   contracts above, not on internal wiring.

---

## 4. Watch-outs

- **Never log** Google tokens, the auth code, the session value, the handoff, the PKCE verifier, or an
  unredacted email -- DEBUG is on for `in.agreementmitra` (D7). Id-only `toString()` on the new entities.
- **No oracle** -- the handshake response is identical for first and returning logins (D3/spec).
- **Leave existing matchers alone** -- this CR only adds the filter + `/api/auth/me` + logout; a regression
  test proves `POST /api/agreements` and `GET /api/agreements/{id}` stay open (`tasks.md` 5.8).
- **Windows dev** -- run container tests with `TESTCONTAINERS_RYUK_DISABLED=true` and the backend with
  `-Duser.timezone=Asia/Kolkata` (repo memory); `run-tests.sh` may silently skip containers on Windows.
- **Lockfile + scans** -- adding the OAuth2-client dependency requires `./gradlew dependencies
  --write-locks` and a re-run of the OSV/SpotBugs gate before the build is green (`tasks.md` 6.2).

---

## 5. Handoff to CR-B

CR-B (`agreement-ownership`) picks up here: it adds `agreement.owner_identity_id` (V12), and uses the
authenticated **identity-id principal** from this CR to implement Save (claim), list-mine with a derived
status, read-scoping, and edit-in-progress -- plus the My-Agreements/Save/Edit UI. Nothing in CR-B changes
this CR's login contracts.

---

## 6. Definition of done

- All `tasks.md` boxes checked; `ModularityTests`, `./gradlew check` (incl. `securityScan`), and
  `npm run security:scan` green.
- Live-driven once: anonymous draft still works with no login; Sign in with Google (sandbox/stubbed) ->
  `/auth/callback` -> `GET /api/auth/me` -> logout; logs confirmed free of tokens/PII/session values.
- `openspec validate` clean; ready for `/opsx:apply` then `/opsx:archive`, then start CR-B.

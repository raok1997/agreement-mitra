# Manual-drive checklist -- bucket 1 (archive-ready pending human validation)

Written 2026-09-05. These are the **only** items standing between seven changes and archive.
Everything automatable has been run and is green (911 backend tests / 156 frontend tests, both OSV
gates, SpotBugs, ModularityTests). What remains needs a human at a browser, or a decision no test
can make.

**Scope:** bucket 1 only. `zoop-aadhaar-esign`, `esign-signature-placement`, `razorpay-payment` and
`signed-delivery-and-closure` are bucket 2 (vendor-blocked -- sandbox creds) and are deliberately
absent; they do not become archivable by driving the UI.

**Stack:** SPA `http://localhost:5174`, API `http://localhost:8090` (both confirmed up).

---

## BLOCKER first -- read before starting

**A real-Google sign-in will fail as the stack is configured right now.**
`GET /api/auth/google/start` returns a `302` to Google carrying `client_id=local-dummy`:
`application-local.yml` has `client-id: ${GOOGLE_OAUTH_CLIENT_ID:local-dummy}` and the variable is
unset (verified live). PKCE + `state` are correctly present, so the request itself is well-formed --
this is a missing credential, not a broken flow.

**Caveat, because I did not establish this:** `google-oauth-login 6.1` is written as *"Sign in with
Google (sandbox client / **stubbed**)"*, and the handshake is already covered e2e by
`GoogleLoginHandshakeIntegrationTest` against WireMock-stubbed Google. So a stub path may already
exist for the SPA -- I only checked the config, not whether you have one wired. **Your call.**

If you need a real client: create one in Google Cloud Console with redirect URI
`http://localhost:8090/api/auth/google/callback`, then restart with `GOOGLE_OAUTH_CLIENT_ID` and
`GOOGLE_OAUTH_CLIENT_SECRET` set.

Either way, **drives C and D need none of this -- do those first.**

---

## Drive A -- Google login handshake  [`google-oauth-login 6.1`]
*Blocked on the credential above.*

1. Open `:5174` and start a draft **without** signing in -- it must work anonymously.
   (Already confirmed at the API: anonymous `POST /api/agreements` -> `201`.)
2. Sign in with Google -> land on `/auth/callback`.
3. Confirm `GET /api/auth/me` returns the identity (the SPA calls it; or curl with the session).
4. Log out.
5. **Then grep the backend log** for any token, `id_token`, session value, or email. The task's real
   assertion is that none of it appears. Do this deliberately -- it is the security clause, and it is
   the half a passing UI flow will not tell you about.

## Drive B -- ownership and resume  [`agreement-ownership 6.1`]
*Blocked on the credential above. Do in the same browser session as A.*

1. Anonymous draft -> Sign in with Google -> Save -> confirm it appears in **My Agreements**.
2. Reopen an in-progress one and edit it.
3. Confirm a **signed** agreement renders read-only.
4. **The clause that matters:** as a *second, different* Google identity, attempt claim / read / edit
   on the first identity's agreement. Every one must answer **404, not 403** -- a 403 would confirm
   the agreement exists and leak ownership.
   - Needs two distinct Google accounts. This is the one assertion no curl and no single-account
     drive can reach, and it is the security core of the change.
   - Already driven at the API: `GET` unknown id -> `404`, `GET` non-UUID -> `400`, unowned draft
     readable anonymously -> `200`. The authenticated-non-owner path is untested.
   - Expect anonymous calls on authenticated routes to answer `403` rather than `401` (Spring
     default, no auth entry point). Not a regression -- different code path from the owner check.

## Drive C -- capture, preview and persistence parity  [`agreement-capture-persistence 7.1`, `preview-centric-capture 7.3`]
*Not blocked. Start here.* One run closes both.

1. On **desktop width**: add an optional section and a dynamic field, and watch the live preview
   fill in as you edit (two-pane shell, sticky sandboxed iframe).
2. Save (signed in) -> confirm it appears in My Agreements -> reopen it -> **the optional section and
   its value are restored**.
3. Confirm **parity**: the preview and the generated draft PDF both show the optional section and the
   dynamic field. Parity is the point -- a difference here is a real bug, not a cosmetic one.
4. On a **narrow viewport** (phone width): confirm the full-screen bottom-sheet section editors, the
   focus trap, and Esc-to-close.
5. Note step 2 needs sign-in, so it inherits the Drive A credential blocker; steps 1, 3 (preview side)
   and 4 do not, and are worth doing now regardless.

Why this one carries extra weight: task 3.7's transitional safety net **never existed** --
`CaptureForm.vue` was converted in place, so this shell is the app's only capture path. There is no
old form to fall back to.

## Drive D -- staff queue  [`staff-queue-fulfilment-context 6.3`]
*Not blocked by Google (staff auth is separate -- confirm how you sign in as STAFF).*

1. Sign in as STAFF, open `/staff`.
2. Confirm a **real** row shows: template, state, and **both parties with father's names**.
3. Confirm the **stamp upload still attaches from the row**.

## Decision D2 -- legal sign-off, not a drive  [`rental-document-content-v2 9.4`]
No browser involved. A lawyer must confirm four authoring choices, each a one-flag change if they
disagree:
- `Statutory (Telangana)` authored **mandatory**
- coarse optional sections **augmented in the shared base**, not replaced in TG
- recital placed in the **witnesseth** section
- **no** TG header override (national wording retained)

---

## Not on this list, and why

- **`post-payment-continuity`** -- not bucket 1. Seven genuine test gaps + three partials + task 10.5
  (resolve/defer design Q1a-Q4 before archive). See its `VERIFICATION-NOTES.md`. It needs an
  implementation pass, not a drive.
- **`template-document-metadata`** -- **RESOLVED 2026-09-05, now 28/28 and archive-ready.** It was
  stale bookkeeping from the "ARCHIVE HELD" entry, as suspected; every box was verified against the
  tree. One real gap turned up (8.3, resolver-level hash coverage for the M0 fields) and was closed
  with two new tests. No drive needed -- it can be archived without touching a browser.

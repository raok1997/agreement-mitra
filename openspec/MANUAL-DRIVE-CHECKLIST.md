# Manual-drive checklist -- bucket 1 (archive-ready pending human validation)

Written 2026-09-05. These are the **only** items standing between seven changes and archive.
Everything automatable has been run and is green (911 backend tests / 156 frontend tests, both OSV
gates, SpotBugs, ModularityTests). What remains needs a human at a browser, or a decision no test
can make.

**Scope:** bucket 1 only. `zoop-aadhaar-esign`, `esign-signature-placement`, `razorpay-payment` and
`signed-delivery-and-closure` are bucket 2 (vendor-blocked -- sandbox creds) and are deliberately
absent; they do not become archivable by driving the UI.

**Stack:** SPA `http://localhost:5173`, API `http://localhost:8090`.

> **The SPA must be on 5173, not 5174.** `vite.config.ts` pins no port, so Vite silently
> auto-increments when 5173 is busy -- and both `spa-callback-uri` and `public-base-url`
> (`application.yml` 270 and 137) default to **5173**. On 5174 the post-consent handoff redirects to
> a dead port and dies as `ERR_CONNECTION_REFUSED` on `/auth/callback#handoff=...`, and outbound
> recovery links point nowhere. An earlier revision of this file recorded 5174 as the confirmed
> stack; that was the stale state written down as fact. Check the Vite banner before driving.
> Worth pinning `server.port: 5173` + `strictPort: true` so a collision fails loudly (not yet done).

---

## CREDENTIALS -- resolved 2026-09-06, read this before concluding "no credential"

**The Google and Razorpay credentials exist** in the repo-root `.env` and `.env.local`
(as `export NAME=...` lines -- a grep anchored to `^[A-Z_]` misses them).

**They were not being read.** `backend/start_local.sh` sources `.env.local` **relative to
`backend/`**, and no such file existed, so the backend fell back to `client_id=local-dummy`. This
is what the previous revision of this file misdiagnosed as a missing credential.

Fix, once:

    ln -s ../.env.local backend/.env.local     # .gitignore's `.env.*` already covers it

Verify before driving -- anything but `local-dummy` is good:

    curl -s -D - -o /dev/null http://localhost:8090/api/auth/google/start | grep -io 'client_id=[^&]*'

Restart with `cd backend && ./start_local.sh`; look for `Loading local env from backend/.env.local`.

**There is no stub sign-in route** (the open question in the previous revision -- now closed).
`AuthController` exposes exactly `google/start`, `google/callback`, `session/exchange`, `me`,
`logout`. The WireMock stub lives only inside `GoogleLoginHandshakeIntegrationTest`. A real Google
OAuth client is mandatory; use a **separate dev client** (redirect URI
`http://localhost:8090/api/auth/google/callback`, JS origin `http://localhost:5173`), never prod's.

**There is no file appender.** `application.yml` sets only `in.agreementmitra: DEBUG`; logs go to
the `bootRun` terminal and nowhere else. Any drive with a log-redaction clause must capture stdout,
or that clause is unverifiable:

    ./start_local.sh 2>&1 | tee /tmp/backend.log

---

## Drive A -- Google login handshake  [`google-oauth-login 6.1`]  -- DONE 2026-09-06, PASSED
*Kept for reference. `google-oauth-login` is 28/28 and archive-ready; redaction scan was clean
(zero token/PII matches; the logged address is masked by `RecipientRedaction`). Logout is NOT
log-evidenced -- `SessionService` logs only on mint -- and was confirmed in the browser instead.
Prod-log redaction is still unrun. Full evidence in that change's `tasks.md` 6.1.*

1. Open `:5174` and start a draft **without** signing in -- it must work anonymously.
   (Already confirmed at the API: anonymous `POST /api/agreements` -> `201`.)
2. Sign in with Google -> land on `/auth/callback`.
3. Confirm `GET /api/auth/me` returns the identity (the SPA calls it; or curl with the session).
4. Log out.
5. **Then grep the backend log** for any token, `id_token`, session value, or email. The task's real
   assertion is that none of it appears. Do this deliberately -- it is the security clause, and it is
   the half a passing UI flow will not tell you about.

## Drive B -- ownership and resume  [`agreement-ownership 6.1`]
*Unblocked as of 2026-09-06 -- the credential problem above is resolved. Do in the same browser
session as A. Still needs **two distinct Google accounts**; if the consent screen is in Testing
mode, both must be added as test users or the second identity bounces and step 4 is unreachable.*

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

## Drive E -- contacts reopen after a failed payment  [`contacts-editable-until-payment 6.2`]
*Added 2026-09-06; postdates this checklist's first revision. Independent of Google -- can be driven
without drives A/B.*

1. Finalise an agreement and reach checkout.
2. **Fail the payment** -- a Razorpay test-mode failure card, or simply dismiss the checkout modal.
   Either produces the non-success the task needs.
3. Reopen the contact step, correct an address, and reach checkout again.

Needs `RAZORPAY_KEY_ID` / `RZP_KEY_SECRET` (present in the root env; see the credentials section).
It does **not** need `RZP_WEBHOOK_SECRET` or a tunnel -- a failed payment never reaches a webhook,
which is why the absence of that variable does not block this drive.

Already driven at the API: `PATCH /api/agreements/{id}/contacts` on an unowned pre-payment draft
answers `200` and accepts a corrected address anonymously; an empty contact set is refused with an
RFC 9457 ProblemDetail. The untested part is the SPA sequence around a *failed* payment.

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

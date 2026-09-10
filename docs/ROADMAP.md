# AgreementMitra — Roadmap

Team-shared, git-tracked source of truth for where the product is and what's
next. Update this in a PR like any other doc. Per-feature intent lives in
`openspec/` (specs + archived changes); deep architecture in
`docs/ARCHITECTURE.md`; vendor specifics in `docs/integrations/`; legal exposure,
template-approval governance and the open questions for counsel in
`docs/LEGAL-POSTURE.md`.

_Last updated: 2026-06-27_

## Where we are

The **signing vertical slice is complete end-to-end on a stubbed eSign
provider**. Hardening (security scanning, SAST, test harness, Spring Security
baseline) and the core signing flow are shipped and tested:

```
agreement -> draft PDF upload -> finalise (order placed) -> payment
          -> staff upload a purchased SHCIL e-stamp scan
          -> STAMPED -> SIGN_REQUESTED -> webhook (HMAC) / reconciliation
          -> signed-artifact storage
          -> signed agreement emailed to both parties + durable in-app copy
          -> agreement CLOSED
```

**The journey now has an end.** `signed-delivery-and-closure` adds the last two
steps: on completion each party is emailed the signed agreement as an
attachment, a party-authenticated in-app copy stays retrievable indefinitely,
and the agreement reaches a terminal **fulfilment** state. Terminal signing
failures (`FAILED`, `EXPIRED`, `STAMP_FAILED`) close as **abandoned**, so dead
work leaves the staff queue instead of accumulating in it. Key properties:

- **Delivery goes only to signing-verified addresses** -- the address the
  invitation was issued to and at which that party completed signing. There is
  no draft-time fallback; an unresolvable party is escalated to staff, never
  guessed at.
- **Exactly once per recipient.** The completion path is re-entered by both the
  webhook and the reconciliation job, so each recipient's record is claimed by a
  guarded conditional update *before* any message is sent.
- **Delivery never blocks or rolls back completion**, and never touches the
  signing FSM -- `SIGNED` stays terminal. Closure is agreement-level fulfilment
  state alongside payment state.
- **The audit trail is never emailed**; it is retained and produced on request.
- Outbound mail goes through a vendor-neutral seam with **one SMTP adapter**
  (free Zoho Mail in dev, ZeptoMail in production) and a **stub active by
  default**, so no test or local run sends anything. See
  `docs/DOMAIN-AND-EMAIL-SETUP.md` section 5b -- including the ZeptoMail account
  review, which is a **2-3 business day lead-time item, not a switch**.

- 13 capability specs under `openspec/specs/`; 15 changes archived.
- FSM: `PDF_GENERATED → STAMPED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED`
  (terminal `STAMP_FAILED` branch off the stamp step).
- 131+ tests green (Testcontainers Postgres + MinIO + WireMock); all build
  gates passing (OSV deps, SpotBugs/FindSecBugs, JaCoCo, ModularityTests).
- Everything runs against a **WireMock/stub** Leegality provider — no live
  vendor credentials have been required to reach this point.

### Done (archived OpenSpec changes)

Hardening: `dev-policy-tightening`, `backend-test-harness`,
`frontend-test-harness`, `backend-security-scanning`,
`bump-spring-boot-security-patches`, `bump-spotbugs-plugin`,
`spring-security-baseline`, `frontend-security-scanning`.

Signing slice: `persistence-foundation`, `agreement-aggregate`,
`validation-error-responses`, `create-signing-request`, `signing-completion`,
`draft-ingestion`, `stamp-composition`.

Guided rental-agreement flow: `rich-agreement-capture` (structured tenant/owner details —
first/last/father name + current address — tenancy start/end dates with a derived duration
in months, full name as per Aadhaar, contact optional at draft; Vue capture screen). Next
in the arc: `agreement-document-render` (CR-3, proposed) — embed the captured data into one
bundled rental template and preview it via a Gotenberg render service. A searchable template
catalog is proposed-but-parked.

Optional Google login — **now complete** across two CRs: `google-oauth-login` (CR-A) delivers
backend-mediated Google OAuth + an opaque server-side session (the SPA never sees a Google
token); `agreement-ownership` (CR-B) attaches save / resume / edit to that identity — an
anonymous draft can be **claimed** into the caller's account, **listed** in "My Agreements"
with a derived status, and **edited** while it is still pre-signing-request. Login stays
**optional**: create + draft-upload + capability read remain fully anonymous. The parked
`mobile-otp-auth` change mirrors CR-B's ownership decisions so a second credential is a
drop-in. Deferred follow-ups (not in CR-B): ownership authZ on the signing/stamping routes
(folded into `signing-auth` below), a retain-and-purge job for unclaimed anonymous drafts,
a cookie session (in-memory only today), and an owner-scoped signed-artifact download surface.

Full-capture persistence — **now complete**: `agreement-capture-persistence` (M5) persists an
agreement's **full capture state** (the flat working-set field map + added optional-section
titles) in a nullable `capture_state jsonb` column (migration `V13`), accepted on create/edit and
returned on read, and **generate-as-draft + preview now render from that stored state** (the fixed
typed columns stay authoritative via render-time reconciliation; a null capture state falls back to
the fixed-column mapping unchanged). This **retires the preview/draft parity STOPGAP** (old
flow-journal 8.4/8.5): optional sections and dynamic field values (e.g. `lockInMonths`, `petAllowed`)
now round-trip on Save and the stored/signed draft matches what the user saw in preview, so the
frontend `NON_PERSISTED_FIELDS` hide-list shrank to only genuinely system-owned template defaults
(`stampDuty`). Deferred (not in M5): normalized per-attribute columns (a jsonb blob suffices to
round-trip and render); write-time validation of the capture map (it is validated at render by the
`documents` projection, the same contract the preview already uses).

## Payment status — a gateway now exists

**Payment is no longer a missing dependency of the fulfilment pipeline.** The
payment gate (state `UNPAID`/`PAID`/`WAIVED`, enforced at e-stamp intake and eSign
initiation) previously had only two producers of a confirmation: a STAFF-recorded
manual payment and a waiver. `razorpay-payment` adds a real one - server-side
order creation with a server-computed amount, Razorpay Standard Checkout inside
the SPA, and confirmation by **verified webhook** (with a reconciliation job as the
fallback). The manual path is unchanged and still works: an out-of-band payment
must remain recordable.

Three facts to carry forward:

- **The gate ships `REQUIRED`.** This reverses the plan the change was written
  under. The product owner's call: "razorpay should be involved before estamping
  by staff". They were told the risk and accepted it - until a live payment has
  been observed settling, a payment bug hard-blocks **all** fulfilment rather than
  merely leaking unpaid work. The escape hatches are operational, not a redeploy:
  a STAFF **waiver** on one agreement (`POST /api/staff/payments/{id}/waive`) for
  money arriving out of band, and `PAYMENT_MODE=OPTIONAL` to relax the gate
  globally if the integration itself breaks. `OPTIONAL` remains a supported,
  separately tested mode - it is just no longer the default.
- **Live-mode verification is still owed.** Task S7.4 of `razorpay-payment` (one
  real payment settling end to end in live mode) has NOT been done, and the gate is
  now enforced regardless. That inverts the usual order of operations, so the first
  live payment is now on the critical path for every order, not just for a
  configuration decision. Watch the stamp queue closely on the first day.
- **The webhook is authoritative; the browser callback is not.** The value
  Checkout returns to the browser is a UX signal only. A customer who pays and
  closes the tab is marked `PAID` by the webhook, never by the callback.

Unpaid orders are deliberately **not filtered out** of the staff stamp queue - the
console shows them as "Awaiting payment" with the upload disabled. Hiding them
would turn "waiting on payment" into "vanished", which is harder to diagnose.

Everything runs against WireMock with fabricated secrets - **test mode only**, no
live Razorpay account exists in this repository. The agreed flat price is a
commercial decision and is a configuration value (`PAYMENT_AMOUNT_MINOR_UNITS`,
integer paise); it has not been set beyond a sandbox placeholder.

## Vendor status (eSign) — read before planning live work

**Decision: the eSign vendor is ZOOP (eSign v5).** See `docs/integrations/zoop.md`.
The adapter (`ZoopEsignProvider`) is implemented and is the **default** provider
(`esign.provider=zoop`); Leegality is retained behind the same `EsignProvider`
seam and selected by configuration, so a rollback is `ESIGN_PROVIDER=leegality`.

**Track B is no longer gated on a vendor account.** ZOOP developer/test access is
**free and self-serve** through their dashboard (a capped transaction count),
against `https://test.zoop.plus/contract/esign` - unlike Leegality's Basic Plan,
which is production-only. The one remaining live item is the **test-environment
tracer** (below), which needs only a self-serve signup, not a support request.

Commercially: Aadhaar eSign is **Rs.10/signature** on ZOOP's Lite tier against
Leegality's Rs.25/signatory - Rs.20 vs Rs.50 for a two-party agreement.

### Legacy: Leegality vendor status (retained adapter)

See `docs/integrations/leegality.md` for full detail. The load-bearing facts:

- **The Basic Plan runs on production only — it is NOT a sandbox.** Signing up
  for Basic does not give test/dummy-data access. A **dedicated developer
  sandbox account must be requested explicitly** (via support@leegality.com /
  the Enquiry Team).
- Repo policy is **sandbox + dummy data only**, so this codebase cannot point
  at the Basic Plan production endpoint. **All live e2e is blocked on the
  developer sandbox account.**
- Real digital stamping (auto-affix, 31 States/UTs) **is** a Leegality feature —
  so eventual real stamping is a swap behind our existing `StampProvider` seam,
  not a separate SHCIL/state-portal build.

## Strategy

Getting a sandbox or real account will take time. **We proceed with everything
that does not need live credentials (Track A) and swap the real integrations in
behind their seams (`EsignProvider`, `StampProvider`) when accounts arrive
(Track B).** The stub/WireMock provider keeps Track A fully testable.

## Track A — proceed now (no live credentials needed) — **PRIORITY**

1. **`signing-auth`** — ownership authZ + rate-limiting + redacted
   security-event logging on the currently-unauthenticated `/api/signing/*` and
   `/api/agreements/*/draft` endpoints. _Highest priority — these are open
   today._
2. **Frontend signing-flow** — no-login self-serve UI per
   `docs/PRODUCT-FEATURE-SET.md`. Surfaces two backend gaps to fill alongside:
   a **status endpoint** and a **signed-artifact fetch endpoint**.

## Track B — ZOOP test access is free and self-serve (no longer blocked)

3. **ZOOP test-environment tracer** — the one deferred manual-test item, and the
   acceptance gate for signature placement. Sign up for free staging access, set
   `ZOOP_APP_ID` / `ZOOP_API_KEY` from env, and run one real `/v5/init` against
   `https://test.zoop.plus/contract/esign` with two dummy Aadhaar signers,
   `SEQUENTIAL`, `send_invite: true`, on a stamped fixture document. Then
   **visually confirm both signatures land inside their intended signature
   zones** — ZOOP measures `x_coord` from the **right** edge while PDF's origin is
   bottom-left, and a mirroring mistake produces confidently wrong output with no
   error, so an assertion over our own arithmetic is not sufficient. Also confirm
   both invitation emails arrive, the 7-day expiry holds, and the webhook arrives
   carrying the `webhook-security-key` header. Record the outcome in
   `docs/integrations/zoop.md` section 5.
   _(The equivalent Leegality sandbox tracer remains blocked on a support-issued
   developer account; it is no longer on the critical path.)_
4. **`leegality-real-stamp`** — **SUPERSEDED** by `manual-estamp-upload`.
   Synthetic stamping is gone; staff now purchase a real SHCIL e-stamp
   out-of-band, scan it, and upload it through the STAFF-only intake endpoint,
   and signing refuses with `409 stamp-required` until one is attached. The
   `StampProvider` seam survives, so Leegality auto-affix (or a real SHCIL
   procurement API) is still a one-adapter swap if it ever becomes worthwhile.
   **Follow-up hardening, not yet scheduled:** SHCIL **online certificate
   verification**. Today staff *attest* to what they uploaded — nothing proves
   the scan matches the certificate number, or that the certificate is genuine.
   That is the single largest residual legal risk in the stamping flow.

## Deprioritized

- **CR-7 `ci-pipeline` (GitHub Actions)** — deprioritized for now. The default
  build goals already fail-close on the gates locally (`./gradlew check` runs
  OSV + SpotBugs + JaCoCo + ModularityTests; the frontend `build` chains
  `security:scan` + tests). CI remains the authoritative long-term home (see
  `docs/TECH_DEBT.md` TD-1) but is not blocking. Revisit before any
  production / real-PII deployment.

## Follow-up register (raised by changes, not yet scheduled)

The single list of follow-ups spun out of an OpenSpec change. Anything a change
identifies but deliberately does not fold in belongs here **before that change is
archived** — the `followUps` line in a change's `.flow-journal.md` moves into
`openspec/changes/archive/` with it and is not a durable record.

| Slug | Scope | Raised by | Date | Priority |
|---|---|---|---|---|
| `agreement-error-problem-type-plumbing` | `AgreementHttpError` carries the RFC 9457 problem `type` at only 1 of 6 throw sites, so the client cannot tell which 409 it got. Customers see the raw `Agreement request failed: 409` for a frozen-terms save and for an ineligible jurisdiction, where written copy exists but is unreachable. Wire the remaining five sites; carry the type on `PaymentHttpError` too. | `contacts-editable-until-payment` | 2026-09-10 | High — customer-visible |
| `frontend-dev-dep-refresh` | Bump `vitest`/`@vitest/mocker` 3.2.7 → 4.1.11 (GHSA-82fw-gwwq-j7x9) and give `scripts/security-scan.mjs` Node globals in the eslint config (2 `no-undef` errors). Until this lands `npm run build` fails for **every** change in the repo, because it chains `security:scan`. | `contacts-editable-until-payment` | 2026-09-10 | High — blocks the frontend build gate repo-wide |
| `agreement-status-detail` | "My agreements" collapses `PDF_GENERATED`/`STAMPED`/`SIGN_REQUESTED` into one "In progress" badge and omits `payment_state` entirely, so awaiting-payment, paid-awaiting-stamp and out-for-signature are indistinguishable. Separately, `@view` and `@edit` both call `openForEdit`, so "View/Download" opens an editable form on a frozen agreement whose only feedback is a raw 409. **Check overlap with the active `agreement-status-link-page` change before proposing — this may belong there.** | `contacts-editable-until-payment` | 2026-09-10 | Medium |

## Other queued non-goals (not scheduled)

**Terms-of-service acceptance checkpoint** — nothing today records that a user
agreed to the terms. `/terms` is linked (landing footer, `LegalDisclaimer` on
the capture/contact/payment screens) but a link is not assent: we store no
timestamp, no terms version, and no per-agreement record of what the customer
accepted. Clause 18 says "the version that applies to an agreement is the
version published when you paid for it", which we currently cannot evidence.
Needs deciding before the first external customer — a checkbox or
click-through at save/pay, persisting the accepted version (the template
fingerprint pattern already in `signing` is the model). Added 2026-09-08.


STAMP_FAILED orphan recovery (**re-scoped**: `PDF_GENERATED` is now a
**durable** state — a request rests there for however long staff take to buy
the e-stamp, potentially days — so it must NEVER be reaped as an orphan; only
the terminal `STAMP_FAILED` branch is a recovery candidate); multi-instance scheduler
distributed lock (ShedLock); prod object-store hardening (SSE +
block-public-access); `.docx` ingestion (LibreOffice-headless → PDF);
our-template generation (Chromium render); multi-state stamp templates;
`draft-revision-after-signing-request` paid supersede flow (see
`docs/future-features/`).

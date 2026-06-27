# AgreementMitra — Roadmap

Team-shared, git-tracked source of truth for where the product is and what's
next. Update this in a PR like any other doc. Per-feature intent lives in
`openspec/` (specs + archived changes); deep architecture in
`docs/ARCHITECTURE.md`; vendor specifics in `docs/integrations/`.

_Last updated: 2026-06-27_

## Where we are

The **signing vertical slice is complete end-to-end on a stubbed eSign
provider**. Hardening (security scanning, SAST, test harness, Spring Security
baseline) and the core signing flow are shipped and tested:

```
agreement → draft PDF upload → auto-stamp (synthetic ₹100 Karnataka)
          → SIGN_REQUESTED → webhook (HMAC) / reconciliation
          → signed-artifact storage
```

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

## Vendor status (Leegality) — read before planning live work

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

## Track B — gated on the Leegality developer sandbox account

3. **Live e2e tracer** — point `LeegalityEsignProvider` at the real sandbox and
   run the happy path with dummy data. Currently the only deferred
   manual-test item.
4. **`leegality-real-stamp`** — swap synthetic stamping for Leegality auto-affix
   behind `StampProvider` (folds in the former SHCIL/state-portal non-goal).

## Deprioritized

- **CR-7 `ci-pipeline` (GitHub Actions)** — deprioritized for now. The default
  build goals already fail-close on the gates locally (`./gradlew check` runs
  OSV + SpotBugs + JaCoCo + ModularityTests; the frontend `build` chains
  `security:scan` + tests). CI remains the authoritative long-term home (see
  `docs/TECH_DEBT.md` TD-1) but is not blocking. Revisit before any
  production / real-PII deployment.

## Other queued non-goals (not scheduled)

PDF_GENERATED / STAMP_FAILED orphan recovery; multi-instance scheduler
distributed lock (ShedLock); prod object-store hardening (SSE +
block-public-access); `.docx` ingestion (LibreOffice-headless → PDF);
our-template generation (Chromium render); multi-state stamp templates;
`draft-revision-after-signing-request` paid supersede flow (see
`docs/future-features/`).

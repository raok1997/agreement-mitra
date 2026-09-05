# Tasks -- agreement-ownership (CR-B: save / resume / edit)

Second of two CRs for optional Google login. **Depends on CR-A (`google-oauth-login`)**: it consumes the
authenticated identity-id principal and the `identity` table. Grouped: schema, then the
`agreement-management` changes (ownership / claim / list / edit + read-scoping), then SecurityConfig, then
the frontend, then the test pyramid, then verify. Decisions (D#) live in `design.md`. Keep `ModularityTests`
green -- only a UUID + a status enum cross the `signing <-> identity` boundary (D1, D3).

## 1. Schema (Flyway V12)

- [x] 1.1 Add `backend/src/main/resources/db/migration/V12__agreement_owner.sql`:
  `alter table agreement add column owner_identity_id uuid references identity(id)` (nullable), plus an
  index on `owner_identity_id`. Forward-only; do not edit V1..V11. Depends on CR-A's `V11` (`identity`) (D7).

## 2. agreement-management -- ownership, claim, list, edit (D1, D2, D3, D4, D5)

- [x] 2.1 Map the nullable `ownerIdentityId` (UUID) onto `Agreement` (plain value, never an `identity`
  type) + `AgreementRepository.findByOwnerIdentityIdOrderByCreatedAtDesc` (D1).
- [x] 2.2 `AgreementService.claim(agreementId, ownerIdentityId)`: conditional set-if-unowned in one
  transaction (`UPDATE ... WHERE owner_identity_id IS NULL` or locked read); idempotent for same owner;
  `404` for unknown/other-owner (no oracle) (D2).
- [x] 2.3 Extend the `signing.SigningRequestQuery` seam to project the `SignatureStatus` for an agreement
  (or return none) -- a plain enum across the boundary, no aggregate (D3).
- [x] 2.4 `AgreementService.listOwnedBy(ownerIdentityId)` -> summaries with the **derived** `status` +
  `editable` flag (map no-request / non-terminal / SIGNED / EXPIRED / FAILED per D3).
- [x] 2.5 `AgreementService.update(agreementId, ownerIdentityId, body)`: owner-scoped, blocked if a signing
  request exists (`409`, reuse `existsForAgreement`), reuse the create-time validator, replace parties
  wholesale, clear the pinned draft (`draft_pdf_key` + template pin), `404` for unknown/other-owner (D4).
- [x] 2.6 `AgreementController` (`signing.api`): `POST /api/agreements/{id}/claim`, `GET /api/agreements`
  (list mine), `PUT /api/agreements/{id}` (edit) -- identity id resolved from the Security context, never a
  body field (anti-mass-assignment) (D1, D2, D4); response record `AgreementSummaryResponse` with
  `status`/`editable`.
- [x] 2.7 Owner-scope `GET /api/agreements/{id}` in the handler: capability-open while unowned; `404` for a
  non-owner (or unauthenticated) once claimed -- indistinguishable from unknown (D5).

## 3. SecurityConfig (D6)

- [x] 3.1 Add `authenticated()` for `GET /api/agreements`, `POST /api/agreements/*/claim`, and
  `PUT /api/agreements/*`, with these matchers **ordered before** the broad `/api/agreements/*` permits;
  leave CR-A's filter + `/api/auth/*` permits and the signing + webhook permits as-is (D6).

## 4. Frontend (D8)

- [x] 4.1 "My Agreements" list view (`GET /api/agreements`): status badge, "Edit" when `editable`,
  "View/Download" when signed, most-recent first; reuses CR-A's auth store for the `Bearer` header.
- [x] 4.2 "Save" (claim) action on a drafted agreement (`POST /{id}/claim`).
- [x] 4.3 Edit flow: load `GET /api/agreements/{id}` into the capture form and submit `PUT`; a lightweight
  guard sends the authenticated views to login when there is no session. Anonymous drafting unchanged.

## 5. Tests (pyramid -- required)

**Unit (no Spring context):**
- [x] 5.1 Derived-status mapping (D3): no request -> `DRAFT`/editable; each non-terminal -> `IN_PROGRESS`/
  not-editable; `SIGNED`/`EXPIRED`/`FAILED`/`STAMP_FAILED` mappings.
- [x] 5.2 Claim state machine (D2): unowned -> owned; same owner idempotent; other owner -> rejected (maps to
  404 at the edge).
- [x] 5.3 Edit guard (D4): allowed when owner + no signing request; rejected (frozen) when a request exists;
  validation reuse (a bad edit body fails the same rules as create); parties replaced wholesale.

**Integration (Testcontainers Postgres + Spring slice):**
- [x] 5.4 Boot-and-validate: app starts against V1..V12 under `ddl-auto: validate` (schema/mapping match).
- [x] 5.5 Claim + read-scoping: anonymous create (owner null, capability-readable by id) -> authenticated
  claim -> now owner-only (`GET /{id}` returns `404` for a different session, no oracle); double-claim by
  another identity -> `404`.
- [x] 5.6 List mine: two identities each with agreements at different statuses -> `GET /api/agreements`
  returns only the caller's, most-recent first, with correct derived status + `editable`.
- [x] 5.7 Edit: owner `PUT` before any signing request updates terms + parties and clears the draft; a `PUT`
  after a signing request exists -> `409`; a non-owner `PUT` -> `404`; owner-source is the session (a body
  `ownerIdentityId`/`id` is ignored).
- [x] 5.8 SecurityConfig matcher order: unauthenticated `GET /api/agreements` -> `401`/`403` while
  unauthenticated `POST /api/agreements` + `GET /api/agreements/{id}` (unowned) stay open.
- [x] 5.9 `ModularityTests` stays green (no `signing -> identity` package dependency; only UUID + status enum
  cross the boundary).

**Frontend (component/e2e-lite):**
- [x] 5.10 My Agreements renders status badges and gates "Edit" on `editable`; Save calls claim; Edit loads
  and PUTs. Anonymous drafting path needs no session.

## 6. Verify + wrap-up

- [ ] 6.1 Live-drive against the running backend (`:8090`) + SPA (with CR-A applied): anonymous draft -> Sign
  in with Google -> Save -> see it in My Agreements -> Edit an in-progress one -> confirm a signed one is
  read-only. Confirm claim/read/edit return `404` (not `403`) for a non-owner.
  - Partially driven 2026-09-05 against the live stack (API half only; the SPA + Google-consent half
    needs a human at a browser -- no browser automation on this host). Anonymous surface confirmed:
    `POST /api/agreements` -> `201`; `GET` own unowned draft -> `200`; `GET` unknown id -> `404`;
    `GET` non-UUID -> `400`; `PUT` terms edit and `GET` list-mine (authenticated routes) -> refused
    anonymously; `PATCH /contacts` on an unowned draft -> `200`.
  - NOT yet driven: the clause this task actually turns on -- an **authenticated non-owner** getting
    `404` on claim/read/edit. That needs two distinct Google identities and cannot be curl'd.
  - Note (not a defect, but worth a decision): an **anonymous** caller on an authenticated route gets
    `403`, not `401`. That is the Spring Security default with no auth entry point. It does not leak
    ownership -- the `404`-not-`403` rule in this task is about the handler-level owner check for an
    authenticated non-owner, which is a different code path. Flagged so a later reader does not read
    the `403` above as a regression.
- [x] 6.2 `./gradlew spotlessApply` then `./run-tests.sh` (or gradle directly with
  `TESTCONTAINERS_RYUK_DISABLED=true` on Windows); `./gradlew check` incl. `securityScan`;
  `npm run security:scan` in `frontend/`.
  - Done (2026-09-05, re-run on a host with `osv-scanner` present): `spotlessApply` clean (no drift);
    `./run-tests.sh` -> `check` BUILD SUCCESSFUL in 3m22s -- 911 tests, 0 skipped, 0 failures,
    0 errors, incl. `ModularityTests`. JaCoCo coverage gate passed.
  - Done: the previously blocked scans now run. Backend `securityScan` green -- `osvScan` over
    `gradle.lockfile` (191 packages) "No issues found"; SpotBugs/FindSecBugs clean. Frontend
    `npm run security:scan` green -- `package-lock.json` (367 packages) "No issues found".
  - Done: frontend `npm run test` GREEN (21 files / 156 tests, up from the 69 recorded when this CR
    was written); `vue-tsc --noEmit` clean.
- [x] 6.3 Update `docs/ROADMAP.md` (optional Google login now complete: login in CR-A, save/resume/edit here)
  and note the deferred follow-ups (signing ownership-authZ, unclaimed-draft purge, cookie session).

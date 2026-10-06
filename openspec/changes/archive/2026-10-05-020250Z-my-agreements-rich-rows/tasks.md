Order: 1 before 2 (`ddl-auto: validate` needs the columns); 2.2 before 3.1 (the entity graph relies on `@OrderBy`); 3 before 4 (the frontend reads the new fields).

## 1. Schema

- [x] 1.1 Add `backend/src/main/resources/db/migration/V22__agreement_last_edited_and_signer_position.sql` per design D3 (add, backfill, NOT NULL, rollback default for `agreement.last_edited_at` and `signer.entry_position`)
- [x] 1.2 Backfill test in `FlywayMigrationIntegrationTest`, mechanics per the design's Testing section:
  - migrate a scratch schema to V21
  - insert one agreement whose tenant row is stored before its two owner rows (schema-qualified names, every NOT NULL column)
  - migrate to V22
  - assert owners get positions 0,1 in stored order, the tenant gets 2, and `last_edited_at = created_at`
  - `DROP SCHEMA ... CASCADE` in `finally`

## 2. Backend domain

- [x] 2.1 `Agreement`: add `lastEditedAt` (column `last_edited_at`), assigned directly in `create` from the same `Instant` as `createdAt`; add package-private `markEdited(Instant)` and a `lastEditedAt()` accessor
- [x] 2.2 `Signer`: add `entryPosition` (column `entry_position`) with accessor; `Agreement.addSigner` sets it to the current list size; add `@OrderBy("entryPosition ASC")` to `Agreement.signers`
- [x] 2.3 Call `markEdited(Instant.now())` at the end of `AgreementService.update`, `AgreementService.updateContacts`, and `DraftService.attachDraft`; grep that these three are its only callers (claim, payment, waiver, stamp, pin, close, and reads never call it); add `lastEditedAt` and `last_edited_at` to `AgreementService.SERVER_MANAGED_CAPTURE_KEYS`
- [x] 2.4 Unit tests in `AgreementTest`: `create` sets `lastEditedAt == createdAt`; `markEdited` moves it; `addSigner` assigns 0..n-1 in call order; `clearSigners` then `addSigner` renumbers from 0 in the new order

## 3. Backend list

- [x] 3.1 `AgreementRepository`: replace `findByOwnerIdentityIdOrderByCreatedAtDesc` with `findByOwnerIdentityIdOrderByLastEditedAtDescCreatedAtDescIdDesc` annotated `@EntityGraph(attributePaths = "signers")`; update `listOwnedBy`
- [x] 3.2 `AgreementSummaryResponse`: add `ownerNames`, `tenantNames`, `lastEditedAt`; fill them in `toSummary`, reading only `name()` and `role()` and splitting by role in position order (a small package-private static helper so it is unit-testable); rewrite the javadoc's PII line per design D5 and correct the tracking-reference note
- [x] 3.3 Unit test for the role-split helper: names split by role and kept in position order
- [x] 3.4 Integration (`AgreementOwnershipIntegrationTest`), list order and shape:
  - create A then B, edit A's terms, list: A first, and A's `lastEditedAt` > `createdAt`
  - two owners + one tenant come back as `ownerNames`/`tenantNames` in entry order
  - each summary object has exactly the documented key set
  - `Cache-Control` contains `no-store`
  - the existing `listReturnsOnlyMineMostRecentFirstWithDerivedStatus` is renamed and reworded to "most recently edited first"
- [x] 3.5 Integration, what moves `last_edited_at` (read before and after with `jdbc`): a parties-only edit (same terms, one name changed), a contacts edit with a changed email (before any payment), and a draft upload each make it later
- [x] 3.6 Integration, what doesn't move it or can't set it (read with `jdbc`):
  - claim (`/claim`), a confirmed payment and a waived payment leave it unchanged. Use the staff endpoints `POST /api/staff/payments/{id}/confirm` and `/waive` via `StaffSessions`, as `PaymentGateIntegrationTest` does; not `support/Payments`.
  - A POST and a PUT carry `lastEditedAt` = 2099-01-01 at the top level and in `captureData`, plus a per-party `position`. Both return 2xx.
    - POST: `last_edited_at = created_at`.
    - PUT: `last_edited_at` is later than before and not 2099.
    - Both: positions follow body order, and `GET /{id}` capture data has no `lastEditedAt`.
- [x] 3.7 Integration, party order:
  - create owner A, owner B, tenant T
  - PATCH A's contacts with a changed email, so A's row is rewritten
  - precondition: `jdbc` `SELECT name FROM signer WHERE agreement_id=? ORDER BY ctid` returns B, T, A
  - assert A, B, T on `GET /{id}` and `ownerNames` = [A, B] on the list
  - then PUT with the owners reversed and assert B before A on both reads

## 4. Frontend

- [x] 4.1 `src/api/agreements.ts`: add `ownerNames`, `tenantNames`, `lastEditedAt` to `AgreementSummary`; update the "terms-only summary" comment
- [x] 4.2 New `src/views/agreementListFormat.ts`: `formatRupees`, `editedAgo(iso, now)`, `matchesQuery(summary, query)` per design D6
- [x] 4.3 Unit tests `src/views/agreementListFormat.test.ts`:
  - `editedAgo`: every band, including a future time ("just now"), 23:00 two calendar days ago (not "yesterday"), and the dd/mm/yyyy fallback
  - `formatRupees`: Indian digit grouping
  - `matchesQuery`: the second tenant, the reference, an address word, several terms (AND), an empty query, case-insensitivity
- [x] 4.4 `MyAgreements.vue` per design D6:
  - search box
  - rich rows
  - expand/collapse by click, Enter and Space, one open at a time, with `aria-expanded`
  - Edit/View with `@click.stop`
  - no-match message
  - responsive Tailwind layout
  - no `v-html`, and query/list/expansion kept only in component state
- [x] 4.5 Component tests in `MyAgreements.test.ts` (fixtures gain the new fields):
  - the first owner/tenant with "+1"/"+2"
  - expanding lists every party in order with roles, the full address and the reference, then collapses
  - Enter toggles a row
  - Edit emits and does not expand
  - "Edited 2h ago" with a pinned clock
  - a markup name renders as text (no `img` element)
  - search filters to the second-tenant match
  - the no-match message appears, and spies on `history.pushState`/`replaceState` and `Storage.prototype.setItem` were never called
  - with no agreements, the empty state shows and the search box does not
  - the existing badge, emit and empty-state tests stay green
- [x] 4.6 Update `App.test.ts` / `agreements.test.ts` fixtures only if the type change breaks them

## 5. Gates

- [x] 5.1 `./run-tests.sh check` from `backend/` green (ModularityTests, coverage, securityScan); report the wall-clock time
- [x] 5.2 `npm run build` and `npm run lint` from `frontend/` green

## Coverage

| Scenario | Disposition | Where |
|---|---|---|
| The list shows in-progress and signed agreements, scoped to the caller | COVERED | 3.4 (existing test, reworded) |
| Editing an older agreement moves it to the top | COVERED | 3.4 |
| Each summary carries every party's name by role, in entry order | COVERED | 3.4 (names, key set, no-store) + 3.3 |
| A new agreement's edit time equals its creation time | COVERED | 2.4 |
| Editing only the parties moves the edit time | COVERED | 3.5 |
| Editing party contacts moves the edit time | COVERED | 3.5 |
| Attaching a draft moves the edit time | COVERED | 3.5 (upload; generate shares `DraftService.attachDraft`) |
| Payment and claim do not move the edit time | COVERED | 3.6 |
| A request cannot set the edit time or a party's position | COVERED | 3.6 |
| Parties read back in entry order after a row is rewritten | COVERED | 3.7 |
| An edit's order replaces the stored order | COVERED | 3.7 + 2.4 |
| Existing parties are backfilled owners first | COVERED | 1.2 |
| A row with several parties shows the first of each and a count | COVERED | 4.5 |
| Expanding a row shows every party and the full address | COVERED | 4.5 |
| Edit does not expand the row | COVERED | 4.5 |
| The edit time reads as relative time | COVERED | 4.5 + 4.3 |
| Markup in a name is shown as text | COVERED | 4.5 |
| Search matches a party who is not listed first | COVERED | 4.5 + 4.3 |
| Search matches the reference and the address | COVERED | 4.3 |
| Several terms must all match | COVERED | 4.3 |
| No match says so | COVERED | 4.5 |
| No agreements shows the empty state, not search | COVERED | 4.5 |

Requirement text without its own scenario: stamping, template pinning, signing progress, closure and reads not moving `lastEditedAt` are checked by the caller grep in 2.3 (validate re-checks it); signing and staff views keeping role-first order is existing behaviour, unchanged.

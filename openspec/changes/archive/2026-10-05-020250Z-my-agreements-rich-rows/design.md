## Context

`GET /api/agreements` (`AgreementController.listMine` → `AgreementService.listOwnedBy` → `toSummary`) returns
`AgreementSummaryResponse`: id, tracking reference, address, rent, dates, duration, `createdAt`, derived status,
`editable`. The SPA's `MyAgreements.vue` shows the address, `reference · N months`, the badge and Edit/View.
The DTO javadoc says "terms only, no party PII"; no spec requirement says so.

Facts that shape the design (grounded 2026-10-04, review round 1):

- `Agreement.signers` is `@OneToMany(mappedBy = "agreement", cascade = ALL, orphanRemoval = true)`, lazy, with no
  `@OrderBy`/`@OrderColumn`. `signer` has no position or timestamp column and random UUID ids, so read order is
  physical heap order. That is insertion order until a row is rewritten (a contacts edit is an `UPDATE`, which
  writes a new row version at the end).
- Signer order is relied on: `SigningRequestService.SIGNING_ORDER` (:432) is a stable sort by role, so within a
  role it follows list order; `AgreementDocumentMapper.firstNameByRole` takes the first of a role; the staff
  `parties` view (:557) sorts by role and claims "stored order preserved within each role".
- Replacing only the parties (`clearSigners` + `addSigner`) or editing contacts (`Signer.updateContacts`) never
  makes the `agreement` row dirty, so a JPA `@PreUpdate`/`@UpdateTimestamp` on `Agreement` would not fire for them.
  It *would* fire for payment, stamping, closure, claim and template pinning.
- The drafting-surface actions have different access rules: `PUT /{id}` is owner-only; `PATCH /{id}/contacts`
  allows the owner or, while unclaimed, the link holder; `POST /{id}/document` and `POST /{id}/draft` check no
  owner at all (`DraftService.attachDraft` uses `findById`). This change does not alter any of them.
- The SPA calls `POST /{id}/document` straight after every create and every edit (`CaptureForm.vue:721-732`).
- `createdAt` comes from `Instant.now()` in `Agreement.create`; there is no `Clock` bean in `signing`.
- `CreateAgreementRequest` requires at least one owner and one tenant (`SignerSetValidator`), 1-20 parties.
- `listOwnedBy` is `@Transactional(readOnly = true)` and already does one status query per row.
- Latest migration is V21. `open-in-view` is off. Boot 3.5 / Flyway 11 ignores applied future migrations, so the
  previous build still starts with V22 applied.

## Goals / Non-Goals

**Goals:**
- Each list row identifies the agreement by its parties, property, rent and dates, and shows when it was last
  worked on.
- The list response is shaped for several owners and tenants, so multi-party capture needs no list change.
- A deterministic, stored party order.
- In-browser search over the loaded list.

**Non-Goals:**
- Delete, archive, duplicate, labels, filters, grouping, granular status (`agreement-status-detail`).
- Server-side search or pagination. Lists are small; revisit when a single owner has hundreds.
- Fixing the existing per-row status query (pre-existing; not made worse).
- Splitting the address into fields.
- Changing who may generate or upload a draft (`draft-attach-owner-gate`).

## Decisions

### D1. `lastEditedAt` is set explicitly by drafting-surface edits, not by a JPA hook

`Agreement` gets `last_edited_at TIMESTAMPTZ NOT NULL` (field `lastEditedAt`) and a package-private
`markEdited(Instant at)`. `Agreement.create` assigns `lastEditedAt` directly from the same `Instant` as
`createdAt`. `markEdited` is called by exactly `AgreementService.update`, `AgreementService.updateContacts`, and
`DraftService.attachDraft` (generate and upload both go through it; pinning runs afterwards in its own
transaction and does not call it). `lastEditedAt` and `last_edited_at` join
`AgreementService.SERVER_MANAGED_CAPTURE_KEYS`, so the stored capture blob can never carry a client value for
it, as already done for `createdAt`. Each passes `Instant.now()`, matching how `createdAt`, `waivePayment` and `close` already take
time. No read path calls it; `listOwnedBy` stays read-only.

*Named for what it means.* `updated_at` would invite a later `@UpdateTimestamp` "cleanup" that silently changes
the list's sort to "any system change". `last_edited_at` says it is the content-edit time.

*Why not `@PreUpdate` / `@UpdateTimestamp`:* it misses the two edits that touch only child rows (parties, contacts)
and fires on every system change, so "Edited 2h ago" would mean "a payment webhook arrived 2h ago". Setting the
field also makes the row dirty for the contacts-only path.

*Why at the service, not inside each domain mutator:* `update` calls five mutators for one edit. One call at the
end of each of the three service methods is one timestamp per action and keeps the domain methods unchanged.
Task 2.3 checks that `markEdited` has exactly these callers.

*Actor:* "edited" means "the content changed through the drafting surface", by whoever each route already
admits. Today that is owner-only for `PUT`, owner-or-unclaimed for contacts, and anyone with the link for draft
attach (register row `draft-attach-owner-gate`). This change adds no access check and grants no access.

*Why not a `Clock` bean:* none exists in the module, and adding one only for this field is a cross-cutting change
for no test benefit. Tests read before/after values back from the database (see Testing), so the JDK's
nanosecond `Instant` vs Postgres microseconds never meet in an assertion.

### D2. Party order is a stored `signer.entry_position`, read with `@OrderBy`

`Signer` gets `entry_position INT NOT NULL` (field `entryPosition`), set by `Agreement.addSigner` to the current
list size (0-based). `clearSigners` + `addSigner` on edit therefore renumbers from 0 in the submitted order.
`Agreement.signers` gets `@OrderBy("entryPosition ASC")`. The name avoids `position`, which is both a Postgres
keyword and an HQL function. Every load returns entry order: the agreement read returns it as is, the list
splits it by role, and the signing flow and staff view keep sorting by role first with their stable sorts, now
over a guaranteed order.

*Why not `@OrderColumn`:* on an inverse (`mappedBy`) collection Hibernate manages the index with extra UPDATEs after
the inserts, and its behaviour with `orphanRemoval` is easy to get wrong. A plain column owned by `Signer` is
explicit and testable.

*No unique `(agreement_id, entry_position)` constraint:* Hibernate 6.6 flushes inserts before orphan deletes
(`ActionQueue` order; verified in review), so a parties edit briefly holds old and new rows with the same
positions. The rollback default (D3) would also break it.

*Signing impact (no FSM change):* `buildSignRequest` and progress sort signers by role with a stable sort, so the
signing order is owners then tenants exactly as today. Within a role, the order is now the stored entry order
instead of heap order. Nothing changes at deploy: the progress view rebuilds its party list from live
`agreement.signers()` (`SigningRequestService` :342-344), and the backfill keeps each role's current heap order,
so every existing agreement's within-role order is the same before and after V22. A later change to the backfill
must keep that property.

### D3. One migration, backfilled, with defaults that keep the previous build working

`V22__agreement_last_edited_and_signer_position.sql`:

```sql
ALTER TABLE agreement ADD COLUMN last_edited_at TIMESTAMP WITH TIME ZONE;
UPDATE agreement SET last_edited_at = created_at;
ALTER TABLE agreement ALTER COLUMN last_edited_at SET NOT NULL,
                      ALTER COLUMN last_edited_at SET DEFAULT now();

ALTER TABLE signer ADD COLUMN entry_position INT;
UPDATE signer s SET entry_position = r.pos
  FROM (SELECT id, row_number() OVER (PARTITION BY agreement_id
          ORDER BY CASE role WHEN 'OWNER' THEN 0 ELSE 1 END, ctid) - 1 AS pos
        FROM signer) r
 WHERE s.id = r.id;
ALTER TABLE signer ALTER COLUMN entry_position SET NOT NULL,
                   ALTER COLUMN entry_position SET DEFAULT 0;
```

*Backfill order:* there is no record of true entry order. Heap order (`ctid`) is what reads return today, but a
contacts edit moves a row to the end, so it is not reliable either. The backfill puts owners first, which matches
how the capture form enters parties and how signing already orders them, and uses heap order within a role.
Consequence: an existing agreement whose rows sit tenant-first in the heap will now show owners first in the edit
form. That is acceptable on beta data, and it is a one-time change.

*Defaults:* they exist only so the previous build, which does not write these columns, can still insert if the
release is rolled back. Hibernate always writes both columns from the entity (`entryPosition` is a primitive), so the
defaults never apply to the new build. Accepted cost: parties inserted by the old build during a rollback all get
position 0 and read back in heap order, as they do today. No index is added; the query is already narrowed by
`idx_agreement_owner_identity_id` to one owner's rows.

### D4. The list loads parties in the same query

`AgreementRepository` replaces `findByOwnerIdentityIdOrderByCreatedAtDesc` (single caller) with
`findByOwnerIdentityIdOrderByLastEditedAtDescCreatedAtDescIdDesc`, annotated
`@EntityGraph(attributePaths = "signers")`, so names arrive with the agreements in one query. One bag only, no
pagination; Hibernate 6 de-duplicates the root rows. The `@OrderBy` must also order the fetched collection, and
the party-order integration test (task 3.7) is what proves it on this path. `toSummary` reads only `name()` and
`role()` from the loaded signers; the other fields (mobile, email, address) are in memory but never mapped or
logged.

### D5. Summary shape

`AgreementSummaryResponse` adds `List<String> ownerNames`, `List<String> tenantNames`, and `Instant lastEditedAt`.
Every existing field stays. Names are `Signer.name()` (the full name handed to eSign), split by role, each in
position order. The javadoc's "no party PII" becomes "party names only, never Aadhaar, VID, father's name,
mobile, email or address", and its stale `AM-<LAST6>-<DDMMYY>` note is corrected to the tracking reference.
Additive JSON fields; the only client is our SPA. `Cache-Control: no-store` already comes from Spring Security's
default headers; a test pins it.

### D6. Frontend

- `src/api/agreements.ts`: `AgreementSummary` gains `ownerNames: string[]`, `tenantNames: string[]`,
  `lastEditedAt: string`.
- New `src/views/agreementListFormat.ts` with pure functions (unit-tested, `now` passed in):
  - `formatRupees(n)`: `en-IN` INR, no decimals.
  - `editedAgo(iso, now)`, by the browser's local calendar:
    - a future time (server clock ahead) or under one minute: "just now"
    - under one hour: "N min ago"
    - the same calendar day: "Nh ago"
    - the previous calendar day: "yesterday"
    - under 30 calendar days: "N days ago"
    - otherwise "on dd/mm/yyyy" via the existing `formatIso`.
  - `matchesQuery(summary, query)`: whitespace-split, lower-cased terms; each must appear in the joined names,
    address or reference.
- `MyAgreements.vue`:
  - Layout: a search input above the list, then rows built with Tailwind: one column on phones, a 5-column grid
    from `md`.
  - Expanding: each row is a `role="button"`, `tabindex="0"` header that toggles an expanded detail (one open row
    at a time) on click, Enter or Space, with `aria-expanded`. The Edit/View button uses `@click.stop`.
  - Text: addresses use `line-clamp-2`; names use `truncate`, with the full name in `title`. Everything is
    rendered by text interpolation; no `v-html`. Dates use `formatIso`.
  - Unchanged: status badges and emits. Copy follows the mockup.
- **Where data lives:** the list, the query and the expanded row live only in component state. Nothing goes in
  the URL, history, `localStorage`/`sessionStorage`, or any log. All of it is dropped on unmount, which sign-out
  already triggers.
- The local INR formatters in `AgreementStatus.vue` and `StampQuoteStep.vue` are left alone: they use different
  precision and are outside this CR's files.

## Risks / Trade-offs

- **The sort no longer follows creation.**
  - [Someone may expect the newest at the top.] → The row says "Edited …", so the order explains itself.
  - [Every SPA save moves `lastEditedAt` twice (save, then generate), and an agreement made in the SPA never shows
    `lastEditedAt == createdAt`.] → Harmless; the creation tie-break mostly matters for backfilled rows.
  - [Claiming does not move the timestamp, so an agreement drafted anonymously days ago lists below newer ones
    after the user signs in.] → Intended: claiming is not an edit.
- [Generating a draft counts as an edit although terms did not change.] → Accepted. It is activity on the
  agreement, and it always follows a save in the SPA.
- [A link holder can attach a draft to a claimed agreement, which now also moves it to the top of the owner's
  list as "Edited just now".] → The access gap is pre-existing and not widened here; tracked as `draft-attach-owner-gate`.
- [Names in a list response widen what one GET returns.] → Same owner, same names as `GET /api/agreements/{id}`.
  This rests on the claim model (`claim-bound-to-initiator`: whoever claims first gets the names). No logging is
  added, and the response is `no-store`.
- [Client-side search won't scale to very long lists.] → Fine at current sizes; server search is a later CR.

## Testing

- "Unchanged"/"later than" checks read `last_edited_at` back with `jdbc` before and after the action. They never
  compare an in-memory `Instant`. Payment and waiver go through the real staff endpoints
  (`/api/staff/payments/{id}/confirm` and `/waive`, via `StaffSessions`), not `support/Payments`, which writes
  raw SQL and could not catch a stray `markEdited` or `@PreUpdate`.
- The party-order checks first make the heap order differ from entry order:
  1. Create A, B, T.
  2. PATCH A's contacts with a **changed** email, before any payment, since contacts freeze at payment.
  3. Assert with `jdbc` (`ORDER BY ctid`) that the stored rows now read B, T, A.
  4. Then assert entry order through the API.

  Without `entryPosition` + `@OrderBy` the API assertions fail.
- The backfill test uses its own scratch schema:
  1. Migrate it to V21 with `Flyway.configure().dataSource(ds).schemas(s).target("21")`.
  2. Insert legacy rows with schema-qualified names and every NOT NULL column.
  3. Migrate to `"22"` and assert.
  4. `DROP SCHEMA s CASCADE` in `finally`.

  Other tests in the cached context count `information_schema`/`pg_indexes` rows without a schema filter, so a
  leftover schema would break them. Never `SET search_path` on a pooled connection.

## Migration Plan

Deploy runs V22 on startup (Flyway). It is additive with backfill, and the defaults keep the previous build
inserting if rolled back. There is no data change beyond the two new columns.

## Open Questions

None. The pre-existing draft-attach access gap found in review round 1 is register row `draft-attach-owner-gate`
(user decision 2026-10-05), not part of this change.

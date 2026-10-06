## Why

The signed-in "My agreements" list shows only the property address (often cut to the city), the tracking
reference and the term in months. Someone handling several agreements, such as a helper drafting for many
clients, cannot tell rows apart: there are no owner or tenant names, no rent or dates, and no sign of which
agreement was worked on last. The list is ordered by creation, so a draft edited this morning can sit below
newer ones nobody has touched.

## What Changes

- The list summary (`GET /api/agreements`) gains the **owner names** and **tenant names**, each as a list in
  the order the parties were entered, and a **`lastEditedAt`** timestamp. Names only: no Aadhaar, phone, email
  or party address. Lists, not single values, so the list needs no change when capture allows several owners
  and tenants (the data model and create API already accept 1-20 parties; only the capture form limits it).
- Parties get a stored **entry position** (`signer.entry_position`, Flyway V22 with backfill), and an agreement's
  parties are always read back in that order. Today the read order is undefined (no `@OrderBy`).
- Agreements get a **`last_edited_at`** column (same migration, backfilled from `created_at`), set when the
  agreement is created, its terms or parties are edited, party contacts are edited, or a draft is attached,
  by whoever those actions already admit. System changes (payment, stamping, signing, closure, claim) do not
  move it.
- The list is ordered **most recently edited first** (ties by creation, newest first), replacing creation
  order. **BREAKING** for the spec's "most-recent first" wording only; the response stays a JSON array of
  the same objects with added fields.
- The My agreements screen is redesigned per the agreed mockup, minimum slice:
  - each row shows the first owner and first tenant with a "+N" count for the rest, the property address
    clamped to two lines, rent per month, start-end dates, the existing status badge, "Edited ⟨relative
    time⟩", and the existing Edit/View button;
  - a row expands to show every party by role, the full address, the reference, the term and the edit date;
  - a search box filters the loaded list in the browser by any party name, the address, or the reference.
- Out of scope (deliberately): deleting drafts (`delete-draft-agreement`, its own CR), status filter chips,
  granular statuses (register row `agreement-status-detail`), group by owner, duplicate/renew, archive,
  property labels, structured address, multi-party capture.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `agreement-management`: the resume-list requirement changes order (most recently edited) and content
  (owner/tenant name lists, `lastEditedAt`); new requirements for the last-edit timestamp, stable party order,
  and the My agreements screen (rows, expansion, search).

## Impact

- **Backend (`signing` module only):** `Agreement` (+`lastEditedAt`, `markEdited`), `Signer` (+`entryPosition`),
  `AgreementService` (`update`, `updateContacts`, `listOwnedBy`, `toSummary`, `SERVER_MANAGED_CAPTURE_KEYS`), `DraftService.attachDraft`,
  `AgreementRepository` (new finder with an entity graph so names load without one query per row),
  `AgreementSummaryResponse`, migration `V22__agreement_last_edited_and_signer_position.sql`.
- **Signing-adjacent read:** the stable party order also feeds `SigningRequestService`'s signer list, which
  already sorts by role and keeps list order within a role. Signing still orders owners before tenants; within
  a role the order becomes the stored entry order instead of heap order. No signing-status FSM transition is touched.
- **Frontend:** `src/api/agreements.ts` (type), `src/views/MyAgreements.vue` (+ tests), a small formatting
  helper for rupees, relative time and search matching.
- **PII/security checklist:** the change moves **party names** (no Aadhaar, OTP, VID, phone, email, or
  address) into the authenticated, owner-scoped list response. The owner already reads the same names via
  `GET /api/agreements/{id}`, so no new party gains access. This rests on the existing claim model
  (register row `claim-bound-to-initiator`). The response stays `Cache-Control: no-store`. The list path logs nothing, and neither do the
  new fields; `Signer.toString` stays id + role. No outbound PII flow, no secrets. Sandbox + dummy data only
  is preserved.

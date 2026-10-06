## MODIFIED Requirements

### Requirement: Resume lists the caller's agreements with a derived status

The system SHALL provide `GET /api/agreements` (authenticated) that returns a summary of the agreements
owned by the caller, most recently edited first (by the last-edit timestamp, ties broken by creation
timestamp newest first, then by id). Each summary SHALL include the property address, monthly rent, start and
end dates, the server-derived duration, the creation timestamp, the last-edit timestamp (`lastEditedAt`), the
names of the agreement's owners (`ownerNames`) and of its tenants (`tenantNames`), a **derived status**, and
an `editable` flag. `ownerNames` and `tenantNames` SHALL each be a list holding every party of that role in
entry order. A summary SHALL carry party **names only** and never a party's Aadhaar number, virtual ID,
father's name, mobile, email, or address. The status SHALL be a read-only projection of the signing state (the
agreement itself stays status-less): an agreement with no signing request SHALL report `DRAFT`
(`editable = true`); one whose signing request is in a non-terminal state (`PDF_GENERATED`, `STAMPED`, or
`SIGN_REQUESTED`) SHALL report `IN_PROGRESS` (`editable = false`); `SIGNED` SHALL report `SIGNED`; `EXPIRED`
SHALL report `EXPIRED`; `FAILED` or `STAMP_FAILED` SHALL report `ACTION_NEEDED`. The list SHALL include both
in-progress and signed agreements. It SHALL return only the caller's agreements and never another identity's.
The response SHALL NOT be cacheable (`Cache-Control: no-store`), and reading the list SHALL change nothing.

#### Scenario: The list shows in-progress and signed agreements, scoped to the caller

- **GIVEN** an authenticated caller who owns a draft, an in-progress agreement, and a signed one, and another
  identity who owns further agreements
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** the system returns exactly the caller's three agreements, most recently edited first, each with
  the correct derived status and `editable` flag, and none belonging to the other identity

#### Scenario: Editing an older agreement moves it to the top

- **GIVEN** an authenticated caller who created agreement A and then agreement B
- **WHEN** the caller edits A's terms and then calls `GET /api/agreements`
- **THEN** A is listed before B, and A's `lastEditedAt` is later than its creation timestamp

#### Scenario: Each summary carries every party's name by role, in entry order

- **GIVEN** an authenticated caller who owns an agreement created with owners "Ramesh Kumar Reddy" then
  "Lakshmi Devi Reddy" and tenant "Priya Sharma"
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** that summary's `ownerNames` is `["Ramesh Kumar Reddy", "Lakshmi Devi Reddy"]` and its
  `tenantNames` is `["Priya Sharma"]`
- **AND** the summary has exactly the documented fields, so no party mobile, email, address, father's name,
  Aadhaar number, or virtual ID
- **AND** the response carries `Cache-Control: no-store`

## ADDED Requirements

### Requirement: An agreement records when its content was last edited

The system SHALL store a last-edit timestamp (`lastEditedAt`) on every agreement, set to the creation time when
the agreement is created and moved to the current time whenever the agreement's own content is changed through
the drafting surface: editing its terms or parties, editing party contacts, or attaching a draft (generated or
uploaded). It moves whoever makes that change, within the access rules each of those actions already enforces;
this requirement grants no new access and changes none. The timestamp SHALL be server-managed and never taken
from a request body, including the stored capture data. System and lifecycle changes (claiming,
payment or a payment waiver, stamping, template pinning, signing progress, closure) and reads SHALL NOT move
it. Agreements that existed before this requirement SHALL start with `lastEditedAt` equal to their creation
timestamp.

#### Scenario: A new agreement's edit time equals its creation time

- **WHEN** an agreement is created
- **THEN** its `lastEditedAt` equals its creation timestamp

#### Scenario: Editing only the parties moves the edit time

- **GIVEN** an agreement owned by the caller with no signing request
- **WHEN** the caller edits it, changing only a party's name and leaving every term unchanged
- **THEN** the agreement's `lastEditedAt` is later than it was before the edit

#### Scenario: Editing party contacts moves the edit time

- **GIVEN** an agreement owned by the caller whose contacts are still editable
- **WHEN** the caller edits a party's mobile or email
- **THEN** the agreement's `lastEditedAt` is later than it was before the edit

#### Scenario: Attaching a draft moves the edit time

- **GIVEN** an agreement with no signing request
- **WHEN** a draft is uploaded to it
- **THEN** the agreement's `lastEditedAt` is later than it was before

#### Scenario: Payment and claim do not move the edit time

- **GIVEN** an agreement with a known `lastEditedAt`
- **WHEN** it is claimed by a signed-in caller, or its payment is recorded or waived
- **THEN** its `lastEditedAt` is unchanged

#### Scenario: A request cannot set the edit time or a party's position

- **WHEN** a create or edit request body carries a `lastEditedAt` far in the future, at the top level and inside
  its capture data, and a per-party `position`
- **THEN** the request succeeds, the stored `lastEditedAt` is server time and not the submitted value, the
  agreement read carries no `lastEditedAt` in its capture data, and the stored order is the order of the
  submitted list

### Requirement: An agreement's parties keep the order they were entered

The system SHALL store each party's entry position within its agreement and SHALL return an agreement's parties
in exactly that order on the agreement read (`GET /api/agreements/{id}`) and in each role's list on the list
summary. Reads that deliberately group by role (the signing flow's signer order and the staff parties view)
SHALL keep owners before tenants and SHALL use the entry order within each role. When the parties are replaced
by an edit, the new list's order SHALL become the stored order. Parties that existed before this requirement
SHALL be assigned positions with owners before tenants, keeping their stored row order within a role.

#### Scenario: Parties read back in entry order after a row is rewritten

- **GIVEN** an agreement created with parties in the order owner A, owner B, tenant T
- **WHEN** A's contacts are edited (which rewrites A's stored row) and the agreement is then read, both by
  `GET /api/agreements/{id}` and in the list
- **THEN** the agreement read returns A, B, T and the list's `ownerNames` is A then B

#### Scenario: An edit's order replaces the stored order

- **GIVEN** an agreement whose owners were entered as "Ramesh Kumar Reddy" then "Lakshmi Devi Reddy"
- **WHEN** the owner edits it, submitting the owners as "Lakshmi Devi Reddy" then "Ramesh Kumar Reddy"
- **THEN** the agreement's parties are returned with "Lakshmi Devi Reddy" first

#### Scenario: Existing parties are backfilled owners first

- **GIVEN** an agreement stored before this requirement, whose tenant row was stored before its owner rows
- **WHEN** the schema migration runs
- **THEN** its owners get positions 0 and 1 in stored row order, its tenant gets position 2, and its
  `lastEditedAt` equals its creation timestamp

### Requirement: The My agreements screen identifies each agreement at a glance

The My agreements screen SHALL show, on each row, the first owner's name and the first tenant's name (each
followed by a "+N" count when that role has further parties), the property address cut to at most two lines,
the monthly rent in rupees, the start and end dates as dd/mm/yyyy, the existing derived-status badge, how long
ago it was last edited ("Edited ⟨relative time⟩"), and the existing Edit or View action. Activating a row (by
pointer or keyboard) SHALL expand it to show every party by role in entry order, the full property address,
the tracking reference, the start and end dates with the term in months, and the edit date; activating it again
SHALL collapse it. The Edit or View action SHALL keep its current behaviour and SHALL NOT toggle the row. A long
party name SHALL be cut with an ellipsis on the row and shown in full when expanded. Names, address and
reference SHALL be rendered as text, never as markup. The screen SHALL keep the server's order.

#### Scenario: A row with several parties shows the first of each and a count

- **GIVEN** an agreement with two owners and three tenants
- **WHEN** the My agreements screen renders it
- **THEN** the row shows the first owner's name with "+1" and the first tenant's name with "+2"

#### Scenario: Expanding a row shows every party and the full address

- **GIVEN** a rendered row for an agreement with two owners, one tenant, and a long address
- **WHEN** the user activates the row
- **THEN** the expanded row lists all three parties labelled Owner, Owner, Tenant in entry order, the full
  address, and the tracking reference
- **AND** activating the row again collapses it

#### Scenario: Edit does not expand the row

- **WHEN** the user presses a row's Edit button
- **THEN** the screen emits the edit action for that agreement and the row does not expand

#### Scenario: The edit time reads as relative time

- **GIVEN** an agreement last edited two hours ago, earlier the same day
- **WHEN** the My agreements screen renders it
- **THEN** the row shows "Edited 2h ago"

#### Scenario: Markup in a name is shown as text

- **GIVEN** an agreement whose owner name is `<img src=x onerror=alert(1)>`
- **WHEN** the My agreements screen renders and expands it
- **THEN** the name appears as literal text and no image element is created

### Requirement: The My agreements screen can be searched

The My agreements screen SHALL provide a search box that filters the already-loaded list in the browser,
without a request to the server. A row SHALL match when every whitespace-separated term of the query appears,
ignoring case, in at least one of: any owner or tenant name, the property address, or the tracking reference.
An empty query SHALL show every row. When no row matches, the screen SHALL say that nothing matches the query
and suggest searching by a name, the address, or the reference. The query SHALL be held only in the screen's
own state: never written to the URL, browser history, or browser storage, and dropped when the screen closes.
When the caller has no agreements at all, the existing empty state SHALL show instead of the search box.

#### Scenario: Search matches a party who is not listed first

- **GIVEN** a list with an agreement whose second tenant is "Mohammed Faizan" and other agreements without that
  name
- **WHEN** the user types "faizan"
- **THEN** only that agreement's row is shown

#### Scenario: Search matches the reference and the address

- **GIVEN** a list of several agreements
- **WHEN** the user types part of one agreement's tracking reference, or a word from its address
- **THEN** that agreement's row is shown and rows matching neither are hidden

#### Scenario: Several terms must all match

- **GIVEN** an agreement with owner "Ramesh Kumar Reddy" at an address in "Banjara Hills", and another with
  owner "Ramesh Kumar Reddy" at an address in "Madhapur"
- **WHEN** the user types "ramesh banjara"
- **THEN** only the Banjara Hills agreement is shown

#### Scenario: No match says so

- **WHEN** the user types a query that matches no agreement
- **THEN** the screen shows a message that nothing matches, suggesting a name, the address, or the reference
- **AND** nothing was written to the browser history or browser storage

#### Scenario: No agreements shows the empty state, not search

- **GIVEN** a caller with no agreements
- **WHEN** the My agreements screen renders
- **THEN** it shows the existing empty state and no search box

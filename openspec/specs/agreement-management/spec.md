# agreement-management Specification

## Purpose

Create and retrieve a multi-party rental agreement — the persisted aggregate (property
and tenancy terms plus a collection of owner/tenant parties), its create-time validation
rules, and the create/read HTTP endpoints. The foundation the signing request and document
rendering build on. (Created by archiving change `agreement-aggregate`; extended by
`rich-agreement-capture` with structured party details — first/last/father name + current
address — tenancy start/end dates, a server-derived duration in months, and a full name as
per Aadhaar.)
## Requirements
### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental agreement
from a JSON body carrying the property and tenancy terms and a collection of parties. On
success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id, a server-assigned id for each party, and the server-computed
tenancy duration.

The client-settable fields SHALL be exactly: the **property address**, the **monthly
rent**, the **security deposit**, the **tenancy start date**, the **tenancy end date**, and
the **party list**. Each party SHALL carry a **first name**, **last name**, **father's
name**, **current address**, a **role** of `OWNER` or `TENANT`, an **optional email** and
an **optional mobile**, and an **optional full-name override**. The agreement id,
each party id, the creation timestamp, the derived term in months, and the computed duration
SHALL be server-assigned and SHALL NOT be accepted from the client (anti-mass-assignment).

Each party SHALL have a stored **full name (as per Aadhaar)**: when the client supplies a
non-blank full-name override the system SHALL use it; otherwise the system SHALL derive it
as the first name followed by the last name. This full name is the name the system uses for
the eSign invitee, so it SHALL be editable by the client to match the signer's Aadhaar
record.

The created agreement SHALL remain **status-less** (the signing-status FSM lives on the
signing-request aggregate).

#### Scenario: Valid richly-detailed agreement is created

- **WHEN** a client POSTs an agreement with a property address, monthly rent, security
  deposit, a start date and a later end date, and two owners and one tenant, each with a
  first name, last name, father's name, and current address
- **THEN** the system persists one agreement and three party rows and responds `201
  Created` with server-assigned ids, the creation timestamp, and the computed duration

#### Scenario: Full name is derived when not supplied, and honoured when overridden

- **WHEN** a client POSTs a party with first name "Asha" and last name "Rao" and no
  full-name override
- **THEN** the system stores that party's full name as "Asha Rao"
- **AND** when another party supplies a full-name override, the system stores the override
  verbatim (so it can match that signer's Aadhaar record)

#### Scenario: Party contact is optional at create

- **WHEN** a client POSTs an otherwise-valid agreement in which one or more parties have no
  email or mobile
- **THEN** the system accepts and persists the agreement `201 Created` (contact is not
  required to draft)

#### Scenario: Client-supplied ids, timestamp, term, and duration are ignored

- **WHEN** a client POSTs an agreement body that also includes an `id`, `createdAt`,
  `termMonths`, or `duration` value
- **THEN** the system ignores those fields, assigns its own id and timestamp, and derives
  the term and duration itself from the dates
- **AND** the response reflects the server-assigned values, not the client's

### Requirement: Signers are multi-party owners and tenants

An agreement SHALL hold a collection of parties, each an addressable entity with a
server-assigned id, a **first name**, a **last name**, a **father's name**, a **current
address**, a **role** of `OWNER` or `TENANT`, an optional email, an optional mobile, and a
stored **full name (as per Aadhaar)** derived from the first and last name unless
overridden. An agreement SHALL support any number of owners and any number of
tenants.

#### Scenario: Multiple owners and tenants retain their full details

- **WHEN** an agreement is created with three owners and two tenants, each with full name
  parts, father's name, and current address
- **THEN** all five parties are persisted as distinct rows, each with its own id and its own
  first name, last name, father's name, current address, role, and stored full name
- **AND** retrieving the agreement returns all five with those details

### Requirement: Create-time agreement validation

The system SHALL reject a create request that violates any of the following with `400 Bad
Request` and SHALL NOT persist any row:

- any party has a blank first name, blank last name, blank father's name, or blank current
  address;
- a party supplies a full-name override that is blank;
- a party's email is present but not a valid email address, or its mobile is present but
  not a valid mobile number;
- the party collection is empty or exceeds the supported maximum (20);
- there is no party with role `OWNER`, or none with role `TENANT`;
- two or more parties that each supply a contact share the same email within the one
  agreement (compared case-insensitively);
- the property address is blank;
- the monthly rent is null or not strictly positive, or the security deposit is null or
  negative, or either money amount has more than two fractional digits;
- the start date or end date is missing, or the end date is not strictly after the start
  date.

The 400 response body SHALL be an RFC 9457 `application/problem+json` document carrying a
field-level `errors` list (per the `api-error-handling` capability): one `{field, message}`
entry per violated constraint, with the cross-field rules mapped to a stable non-null
`field` token. Each `message` SHALL be phrased for a non-technical user (this is a public,
all-India application), and the body SHALL NOT echo the rejected input value or any
submitted PII.

#### Scenario: Missing a required party name part is rejected

- **WHEN** a client POSTs an agreement where a party has a blank father's name or blank
  current address
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: End date not after start date is rejected

- **WHEN** a client POSTs an agreement whose end date equals or precedes its start date, or
  whose start or end date is missing
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Missing a required role is rejected

- **WHEN** a client POSTs an agreement whose parties are all `OWNER` (no `TENANT`)
- **THEN** the system responds `400 Bad Request` and persists nothing
- **AND** the problem+json `errors` list identifies the party-set rule

#### Scenario: Duplicate party contacts are rejected

- **WHEN** a client POSTs an agreement where two parties supply the same email
- **THEN** the system responds `400 Bad Request` and persists nothing
- **AND** the response body does not echo the duplicated email value

### Requirement: Retrieve an agreement by id

The system SHALL provide `GET /api/agreements/{id}` that returns the persisted agreement, its parties, and
the server-computed tenancy duration. **Access SHALL depend on ownership:** while the agreement is
**unowned** it SHALL be readable by any caller presenting its id (the unguessable id is a bearer capability,
unchanged from today); once the agreement is **claimed** it SHALL be readable only by its owner, and a
non-owner or unauthenticated caller SHALL receive `404 Not Found` (indistinguishable from an unknown id, so
ownership cannot be probed). For an unknown id it SHALL respond `404 Not Found` with an RFC 9457
`application/problem+json` body (per the `api-error-handling` capability); the body SHALL NOT leak internal
detail. A syntactically invalid id (not a UUID) SHALL respond `400 Bad Request` as problem+json, not 404.

#### Scenario: An unowned agreement is readable by id

- **WHEN** a client GETs `/api/agreements/{id}` for an unowned agreement
- **THEN** the system responds `200 OK` with the property address, monthly rent, security deposit, start
  date, end date, the computed duration, the creation timestamp, and every party's details

#### Scenario: A claimed agreement is owner-scoped

- **GIVEN** an agreement claimed by one identity
- **WHEN** a different (or unauthenticated) caller GETs `/api/agreements/{id}` for it
- **THEN** the system responds `404 Not Found` as `application/problem+json`, indistinguishable from an
  unknown id

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client GETs `/api/agreements/{id}` for an id that does not exist
- **THEN** the system responds `404 Not Found` as `application/problem+json`

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client GETs `/api/agreements/not-a-uuid`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`

### Requirement: Tenancy duration is derived from start and end dates, in months

The tenancy SHALL be defined by its **start and end dates**, which are the source of truth.
The system SHALL derive, on the server, the **tenancy duration in whole months** (the number
of complete months between the start and end dates, exclusive of the end date) and SHALL
include it in create and retrieve responses as the value shown to the user. The duration
SHALL NOT be accepted from the client; it SHALL be recomputed from the dates so it cannot
drift from them. A legacy `termMonths` value MAY be retained internally, but it SHALL be
the same server-derived whole-month count, never a client-supplied field.

#### Scenario: Duration reflects the date span in months

- **WHEN** an agreement runs from a start date to an end date eleven whole months later
- **THEN** the response reports the duration as eleven months

#### Scenario: Duration is recomputed, never client-supplied

- **WHEN** a client includes a `termMonths` or `duration` in the create body that disagrees
  with its dates
- **THEN** the system ignores the supplied value and returns the duration computed from the
  start and end dates

### Requirement: Agreement schema is Flyway-managed

The `agreement` and `signer` tables SHALL be created by a forward-only Flyway
migration (`V2__agreement.sql`); the JPA mapping SHALL match the migrated schema so
the application boots under `ddl-auto: validate`. The `signer` table SHALL carry a
foreign key to `agreement` and an index on that foreign key. The migration SHALL
NOT edit the existing `V1` baseline.

The structured party fields (`first_name`, `last_name`, `father_name`, `current_address`,
`mobile`) and the tenancy dates (`start_date`, `end_date`) SHALL be added by a later
forward-only **additive** migration (`V7__rich_agreement_capture.sql`) that keeps the
existing `name` and `term_months` columns (making `email` nullable); the migration SHALL
NOT edit any prior migration, and the JPA mapping SHALL match the migrated schema.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations
  (including `V7`) have been applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the
  migrated `agreement` and `signer` tables) and the context starts

### Requirement: Agreement carries a server-managed draft reference

The `Agreement` aggregate SHALL carry a **server-managed** reference to its uploaded draft
PDF — a nullable object-storage **key** (`draftPdfKey`), null until a draft is uploaded.
The reference SHALL be set only by the server (via the draft-upload flow) and SHALL NOT be
client-settable on create or any other request (anti-mass-assignment). Persisting this
reference SHALL NOT introduce a signing-status field — the agreement remains
**status-less**.

The `draft_pdf_key` column SHALL be added to the `agreement` table by a new forward-only
Flyway migration (`V5__agreement_draft.sql`) as a **nullable** column, and the JPA mapping
SHALL match the migrated schema so the application boots under `ddl-auto: validate`.

#### Scenario: New agreement has no draft reference

- **WHEN** an agreement is created via `POST /api/agreements`
- **THEN** its `draftPdfKey` is null (no draft uploaded yet)
- **AND** the create request cannot set `draftPdfKey` even if supplied in the body

#### Scenario: Draft reference is set by the server after upload

- **WHEN** a draft is uploaded for the agreement
- **THEN** the agreement's `draftPdfKey` is populated by the server with the deterministic
  storage key and persists across reads

### Requirement: Agreement carries server-managed stamp info

The `Agreement` aggregate SHALL carry **server-managed stamp data** as an embedded,
all-nullable `StampInfo` value object - the **certificate number**, `stampedPdfKey`
(object-storage key of the composited instrument), `scanKey` (object-storage key of the
uploaded certificate scan), **duty amount**, `jurisdiction`, **certificate issue date**,
`dutyPaid`, and `attachedAt` - null/empty until a stamp is attached. It MAY additionally carry
the optional **description of document** and **purchased by** values recorded at intake.

This data is **descriptive only**: it SHALL NOT introduce a signing-status field and the
agreement SHALL remain **status-less** (the stamp lifecycle lives on the signing request, per
`signing-request`). `StampInfo` SHALL be set only by the server (during staff stamp intake)
and SHALL NOT be client-settable on create or any other request (anti-mass-assignment).
`StampInfo` SHALL NOT appear on the public `AgreementResponse`; it SHALL be reachable only
through an internal accessor (mirroring `draftPdfKey`). Neither the stamped PDF bytes nor the
scan bytes SHALL be stored in PostgreSQL - only the keys are persisted.

The certificate number SHALL carry a **database-level uniqueness constraint** across all
agreements, enforcing the single-use rule in `estamp-intake`.

The new stamp columns SHALL be added to the `agreement` table by a new forward-only Flyway
migration as **nullable** columns, and the JPA mapping SHALL match the migrated schema so the
application boots under `ddl-auto: validate`. The migration SHALL NOT edit any existing
applied migration.

#### Scenario: New agreement has empty stamp info

- **WHEN** an agreement is created via `POST /api/agreements`
- **THEN** its `StampInfo` fields are all null (no stamp attached yet)
- **AND** the create request cannot set any `StampInfo` field even if supplied in the body

#### Scenario: Stamp info is set by the server at staff intake

- **WHEN** a staff user uploads an e-stamp certificate for the agreement
- **THEN** the agreement's `StampInfo` is populated by the server (certificate number,
  stamped-PDF key, scan key, duty amount, jurisdiction, issue date, `dutyPaid = true`,
  `attachedAt`) and persists across reads

#### Scenario: Certificate number is unique across agreements

- **WHEN** a certificate number already recorded on one agreement is written to another
- **THEN** the database rejects the write and the second agreement keeps empty stamp info

#### Scenario: Stamp info is not exposed on the public response

- **WHEN** a client GETs `/api/agreements/{id}` for an agreement that has a stamp
- **THEN** the `AgreementResponse` body does not include any `StampInfo` field

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations have been
  applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the migrated
  `agreement` stamp columns) and the context starts

### Requirement: Generate-as-draft pins the effective-template identity for reproducibility

The system SHALL, when generating and storing the signable draft (generate-as-draft), render the
document definition-driven (projection **generate** mode -- full validation) and **pin** the effective
template's identity onto the agreement: its `contentHash` and the resolved **layer versions**
(`layerId -> version`). The pin SHALL be server-managed (never client-settable) and set only after a
successful full render and draft storage. A stored/signed draft SHALL therefore be reproducible from
its pin and SHALL NOT be silently re-rendered against newer layers. A **stateless preview** SHALL NOT
pin anything. The pin SHALL reuse the existing generate-as-draft transition and SHALL introduce no new
signing-status FSM state; the draft-freeze conflict (a signing request already exists) SHALL be
unchanged.

The pin columns SHALL be added to the `agreement` table by a new forward-only Flyway migration
(`V9__agreement_template_pin.sql`) as **nullable** columns (`template_content_hash TEXT`,
`template_layer_versions JSONB`); it SHALL NOT re-add `template_id` (owned by `V8`) and SHALL NOT edit
any existing migration (`V1`--`V8`). The JPA mapping SHALL match the migrated schema so the application
boots under `ddl-auto: validate`.

#### Scenario: Generate-as-draft records the pin

- **WHEN** a client invokes generate-as-draft for an agreement and the full render + draft storage
  succeed
- **THEN** the system records the effective template's `contentHash` and layer versions on the
  agreement, alongside the stored draft PDF

#### Scenario: A stateless preview records no pin

- **WHEN** a client requests a stateless document preview
- **THEN** no template identity is pinned and nothing is persisted

#### Scenario: The pin is server-managed only

- **WHEN** an agreement is created or updated through any client-facing request body
- **THEN** the pin columns cannot be set by the client and remain null until generate-as-draft records
  them

#### Scenario: The migration applies forward-only and validates

- **WHEN** the application starts with `V9__agreement_template_pin.sql` present and JPA `ddl-auto:
  validate`
- **THEN** the migration adds the two nullable pin columns without editing prior migrations and the
  `Agreement` mapping validates against them

### Requirement: Agreement carries a single, unique tracking reference

Each agreement SHALL carry **exactly one** externally-visible reference. The **same** value SHALL
be the number shown to the customer, the number staff use to locate the agreement when uploading
an e-stamp, and the reference rendered on the document. The system SHALL NOT maintain two
different customer-facing and staff-facing references for one agreement.

The reference SHALL be **persisted, unique, and collision-free**, assigned by the server at
agreement creation, immutable for the life of the agreement, and carrying a database-level
uniqueness constraint.

It SHALL be safe to read aloud and re-type, because it travels through a manual loop: the
customer receives it, staff read it from the order, purchase the stamp, and type it back in
hours or days later. It SHALL therefore use an unambiguous alphabet and carry a check character,
so a single-character typo is rejected rather than resolving to a different agreement.

The **derived, display-only** `AM-<LAST6>-<DDMMYY>` form SHALL NOT be used as a second reference.
Because it is computed at render time and is not collision-free, it SHALL be replaced by this
persisted reference everywhere it was previously surfaced - including the customer-facing
response and the document provenance line.

The reference SHALL NOT be guessable in a way that lets a caller enumerate other agreements, and
possessing it SHALL NOT by itself authorise any operation - stamp intake still requires the STAFF
role (per `estamp-intake`).

#### Scenario: Every agreement gets a unique reference at creation

- **WHEN** an agreement is created
- **THEN** the server assigns it a reference that is unique across all agreements and is
  persisted with the agreement

#### Scenario: Customer and staff see the same number

- **WHEN** the customer is shown their tracking reference and staff locate the same agreement to
  upload a stamp
- **THEN** both use the identical value, and no second reference exists for that agreement

#### Scenario: The document carries the same reference

- **WHEN** the agreement document is rendered
- **THEN** its provenance line shows the persisted reference, not a separately-derived value

#### Scenario: A single-character typo is rejected

- **WHEN** a reference is submitted with one character mistyped
- **THEN** the check character causes it to be rejected rather than resolving to another
  agreement

#### Scenario: The reference is immutable

- **WHEN** an agreement is updated after creation
- **THEN** its reference is unchanged

#### Scenario: The reference alone grants no access

- **WHEN** a non-staff caller submits a valid reference to the stamp-intake endpoint
- **THEN** the request is refused on authorization grounds, unaffected by the reference being
  correct
### Requirement: Generate the signable draft from the agreement

The system SHALL provide `POST /api/agreements/{id}/document` that renders the agreement's rental
document and stores it as the signable draft, then pins the effective template's identity. The render
SHALL use the agreement's **stored capture state** when present -- feeding the stored working-set data
map (reconciled so the authoritative fixed columns win) and the stored `activeSections` into the
document projection -- so the **stored/signed draft matches the live preview the user saw** (preview and
draft parity). When the agreement has **no** stored capture state, the render SHALL fall back to the
fixed-column mapping with no optional sections, exactly as before. The document reference in the
provenance line SHALL remain the agreement's derived tracking number. Storing the draft SHALL remain
frozen once a signing request exists (`409`), and the pin SHALL be recorded only after a successful
store.

#### Scenario: The stored draft renders the added optional sections

- **GIVEN** an agreement saved with an added optional section and a dynamic field value
- **WHEN** the draft is generated
- **THEN** the stored draft PDF contains that optional section and the dynamic value, matching what the
  preview showed

#### Scenario: A legacy agreement with no capture state renders as before

- **GIVEN** an agreement persisted before this change (null capture state)
- **WHEN** its draft is generated
- **THEN** the render uses the fixed-column mapping with no optional sections, unchanged from prior
  behaviour

### Requirement: An agreement persists its full capture state

An agreement SHALL persist its **capture state**: the flat working-set field map (field key -> value)
that produced its document, plus the list of added **optional sections**. The system SHALL accept an
optional `captureData` map and an optional `activeSections` list on the create body
(`POST /api/agreements`) and on the edit body (`PUT /api/agreements/{id}`), and SHALL store them on the
agreement as server-managed content. The capture map SHALL NOT be a channel for server-managed fields:
the agreement id, `owner_identity_id`, creation timestamp, derived duration, and template pin SHALL
remain server-managed and SHALL be ignored if present as map keys (anti-mass-assignment). The fixed
rental columns (property address, monthly rent, security deposit, dates, parties) SHALL remain
authoritative; where the capture map repeats them it SHALL NOT override the typed values. When no
`captureData` is supplied (an API client sending only fixed fields), the agreement SHALL persist a null
capture state and behave exactly as before.

#### Scenario: Create stores the capture state

- **WHEN** a client POSTs a valid agreement whose body includes `captureData` and `activeSections`
- **THEN** the system persists the agreement together with its capture state and returns `201 Created`

#### Scenario: A capture map cannot set server-managed fields

- **WHEN** a create or edit body's `captureData` contains keys for the id, owner, createdAt, duration, or
  template pin
- **THEN** the system ignores those keys; those fields stay server-managed

#### Scenario: An API client sending only fixed fields is unaffected

- **WHEN** a client POSTs a valid agreement with no `captureData`
- **THEN** the system persists a null capture state and the agreement renders exactly as it did before
  this change

### Requirement: A saved agreement round-trips its capture state on read

`GET /api/agreements/{id}` and the "my agreements" edit-reload SHALL return the agreement's stored
`captureData` and `activeSections` (subject to the existing owner-scoping) so a client can restore the
optional sections and dynamic field values a user entered. An agreement with a null capture state SHALL
return no capture state (the fixed fields and parties, as before).

#### Scenario: Reopening an owned agreement restores its optional sections

- **GIVEN** an owner who saved an agreement with an added optional section and a dynamic field value
- **WHEN** the owner reads it for edit
- **THEN** the response includes the stored `captureData` and `activeSections`, so the form re-activates
  the optional section and repopulates the dynamic value

### Requirement: An agreement may be created anonymously and later owned

An agreement SHALL be creatable with **no owner**: `POST /api/agreements` requires no authentication and
persists the agreement with a null `owner_identity_id`, addressed by its unguessable server UUID (a
capability handle), exactly as today. An agreement SHALL carry a nullable server-managed
`owner_identity_id` that references an `Identity`. Ownership SHALL be server-sourced from the caller's
session and SHALL NOT be accepted from any request body (anti-mass-assignment). The create body, its
validation, and its anti-mass-assignment guarantee are otherwise unchanged.

#### Scenario: Anonymous create leaves the agreement unowned

- **WHEN** an unauthenticated client POSTs a valid agreement
- **THEN** the system persists it `201 Created` with a null owner, readable by anyone presenting its id

#### Scenario: A client-supplied owner is ignored

- **WHEN** a create (or edit) body includes an `ownerIdentityId` value
- **THEN** the system ignores it; ownership is set only from the session, only by claim
### Requirement: Save binds an unowned draft to the caller (claim)

The system SHALL provide `POST /api/agreements/{id}/claim` (authenticated) that sets the agreement's
`owner_identity_id` to the caller's identity **only if the agreement is currently unowned**. Claim SHALL be
idempotent for the same owner (a repeat claim returns `200`). If the id is unknown, or the agreement is
already owned by a **different** identity, the system SHALL respond `404 Not Found` -- the same response for
both, so claim is not an ownership or existence oracle. The check-and-set SHALL be atomic so two concurrent
claims cannot both succeed.

#### Scenario: Claiming an unowned draft succeeds and is idempotent

- **GIVEN** an unowned agreement
- **WHEN** an authenticated caller claims it, then claims it again
- **THEN** the first claim sets the owner to the caller and the second returns `200` with no change

#### Scenario: Claiming someone else's agreement is indistinguishable from unknown

- **WHEN** an authenticated caller claims an id that is unknown, or one already owned by a different identity
- **THEN** the system responds `404 Not Found` in both cases
### Requirement: Resume lists the caller's agreements with a derived status

The system SHALL provide `GET /api/agreements` (authenticated) that returns a summary of the agreements
owned by the caller, most-recent first. Each summary SHALL include the property address, monthly rent, start
and end dates, the server-derived duration, the creation timestamp, a **derived status**, and an `editable`
flag. The status SHALL be a read-only projection of the signing state (the agreement itself stays
status-less): an agreement with no signing request SHALL report `DRAFT` (`editable = true`); one whose
signing request is in a non-terminal state (`PDF_GENERATED`, `STAMPED`, or `SIGN_REQUESTED`) SHALL report
`IN_PROGRESS` (`editable = false`); `SIGNED` SHALL report `SIGNED`; `EXPIRED` SHALL report `EXPIRED`;
`FAILED` or `STAMP_FAILED` SHALL report `ACTION_NEEDED`. The list SHALL include both in-progress and signed
agreements. It SHALL return only the caller's agreements and never another identity's.

#### Scenario: The list shows in-progress and signed agreements, scoped to the caller

- **GIVEN** an authenticated caller who owns a draft, an in-progress agreement, and a signed one, and another
  identity who owns further agreements
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** the system returns exactly the caller's three agreements, most-recent first, each with the correct
  derived status and `editable` flag, and none belonging to the other identity
### Requirement: An owner may fully edit an in-progress agreement

The system SHALL provide `PUT /api/agreements/{id}` (authenticated) that fully replaces the mutable terms of
an agreement the caller owns -- the property address, monthly rent, security deposit, tenancy start and end
dates, and the party collection -- using the same request shape and the same create-time validation as
`POST /api/agreements`. Editing SHALL be permitted **only while no signing request exists** for the
agreement; once a signing request exists the agreement is frozen and the edit SHALL respond `409 Conflict`
(the same rule that freezes the draft). A caller that does not own the agreement, or an unknown id, SHALL
receive `404 Not Found` (never `403`; no oracle); an unowned anonymous draft SHALL NOT be editable through
this route. The agreement id, owner, creation timestamp, and derived duration SHALL remain server-managed and
SHALL NOT be accepted from the body. A successful edit SHALL clear the pinned draft reference so a subsequent
generate produces a fresh document from the edited terms.

#### Scenario: Owner edits terms and parties before signing is requested

- **GIVEN** an authenticated caller who owns an agreement with no signing request
- **WHEN** the caller PUTs a body changing the monthly rent, the dates, and the party list
- **THEN** the system revalidates and persists the new terms and parties, recomputes the duration, clears the
  pinned draft, and returns `200 OK` with the updated agreement

#### Scenario: Editing after signing is requested is frozen

- **GIVEN** an owned agreement for which a signing request already exists
- **WHEN** the owner PUTs an edit
- **THEN** the system responds `409 Conflict` and changes nothing

#### Scenario: A non-owner edit is indistinguishable from unknown

- **WHEN** an authenticated caller PUTs an edit to an id they do not own, or an unknown id
- **THEN** the system responds `404 Not Found` in both cases

#### Scenario: An invalid edit is rejected by the same rules as create

- **WHEN** an owner PUTs an edit whose body violates a create-time rule (for example a blank party name, no
  `TENANT`, a non-positive rent, or an end date not after the start)
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and changes nothing

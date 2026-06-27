## ADDED Requirements

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

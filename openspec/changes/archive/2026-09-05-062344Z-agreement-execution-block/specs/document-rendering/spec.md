## ADDED Requirements

### Requirement: Execution / signature block with eSign anchors

The renderer SHALL render an execution / signature block for the agreement's signer set, filling the
previously-empty "In Witness Whereof" section. For each signer it SHALL render a signature zone
(signature area, the signer's name, and the field's role label) and SHALL emit a stable, non-PII eSign **anchor**
identified by role (`esign:<role>`). The block, its clauses, and its anchors are part of the effective
template (system-owned markup); all signer-supplied values are escaped. Witness lines SHALL render
only when the optional Witnesses section is added (opt-in via activeSections -- the engine's standard
optional-section gating; the section and its witness fields default off), as printed escaped data
without an eSign anchor. The renderer SHALL remain eSign-agnostic (it emits an anchor token, not a
provider signature field).

The zone SHALL NOT render a date or place line: an eSigned instrument takes its date from the eSign
appearance, so those blanks could never be completed. The label SHALL state the signer's role only
and SHALL NOT claim the name matches an Aadhaar record, because nothing in this flow verifies the
captured name against one.

#### Scenario: Signature zone + anchor per signer

- **GIVEN** an agreement with an Owner and a Tenant
- **WHEN** the document is rendered (preview or generate-as-draft)
- **THEN** the "In Witness Whereof" section renders an execution block with one signature zone for the
  Owner and one for the Tenant
- **AND** each zone shows a signature area, the signer's name, and the role label -- and no date or
  place line
- **AND** the output contains a stable anchor `esign:owner` and `esign:tenant`, one per zone

#### Scenario: Signer data is escaped in the execution block

- **GIVEN** a signer whose name contains `<script>alert(1)</script>`
- **WHEN** the execution block renders
- **THEN** the value appears escaped as literal text (`&lt;script&gt;...`), never as active markup

#### Scenario: Witnesses are an optional section, default off

- **GIVEN** an agreement whose optional Witnesses section is not added (the residential default)
- **WHEN** the document renders
- **THEN** no witness lines appear
- **AND GIVEN** the Witnesses section is added with witness name / address, **WHEN** it renders,
  **THEN** the corresponding witness lines appear as printed (escaped) data, without an eSign anchor

#### Scenario: Anchors are reproducible under the version pin

- **GIVEN** a generated draft whose effective template is pinned
- **WHEN** the document is re-rendered from the pin
- **THEN** the execution block and both eSign anchors reproduce byte-stable

### Requirement: Boilerplate clauses and document furniture

The renderer SHALL render the boilerplate clauses declared in the template (notices, governing law,
severability, entire-agreement / amendment) and SHALL render document furniture: a unique, non-PII
agreement reference on the document and a page indicator (page X of Y).

#### Scenario: Boilerplate clauses present

- **WHEN** a rental-agreement document renders
- **THEN** it includes a Notices clause, a Governing-law clause, a Severability clause, and an
  Entire-agreement / Amendment clause

#### Scenario: Agreement reference and page numbers

- **WHEN** a rental-agreement document renders
- **THEN** every page shows a unique agreement reference and a "page X of Y" indicator
- **AND** the reference contains no Aadhaar / OTP / VID / secret

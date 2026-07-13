## MODIFIED Requirements

### Requirement: An agreement draft may be system-generated from a template

An agreement's draft PDF SHALL be obtainable **either** by upload (the existing path) **or** by
**system generation** from the agreement's **selected effective template** via `POST
/api/agreements/{id}/document`. When the agreement carries a selected template, the system SHALL
render the effective template resolved for that template's `(state, type)` dimensions; when the
agreement carries **no** selection, the system SHALL render the **default** effective template
(unchanged from before this change). However the draft is produced, it SHALL be stored under the
same object-storage draft key and SHALL feed the existing stamp and eSign flow unchanged;
downstream steps SHALL NOT distinguish an uploaded draft from a generated one. On success the
endpoint SHALL respond `200 OK` and SHALL NOT return the stored bytes. For an unknown agreement id
it SHALL respond `404 Not Found`; a non-UUID id SHALL respond `400 Bad Request`.

Generation SHALL record the **reproducibility pin** (the composed effective template's content hash
+ layer versions) of the template it **actually rendered**, so a selected non-default template is
pinned to its own identity -- a stored/signed draft can never be silently re-rendered against a
different or newer template. Template-declared fields the agreement does not persist SHALL render
from the effective template's **declared defaults** (no per-agreement attributes store in this
change).

Generating a document SHALL be permitted while the agreement has **no signing request yet**, and
SHALL **overwrite** any prior draft (uploaded or generated). Once a signing request exists for the
agreement (in any state), the draft SHALL be treated as locked and generation SHALL be rejected with
`409 Conflict`, leaving the existing draft unchanged -- the same freeze rule the upload path
enforces, so a document cannot change under an in-flight signature.

#### Scenario: A generated draft feeds signing like an uploaded one

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an existing agreement with no signing
  request
- **THEN** the system renders the agreement's selected effective template and stores the PDF as that
  agreement's draft under the existing draft key
- **AND** a subsequent signing request finds the generated PDF as the draft and proceeds exactly as
  it would for an uploaded draft

#### Scenario: A selected non-default template is rendered and pinned

- **WHEN** an agreement created with `(TG, residential)` is generated as a draft
- **THEN** the stored draft is rendered from the Telangana effective template (not the national
  default)
- **AND** the recorded reproducibility pin's content hash equals that Telangana effective template's
  content hash, not the default one

#### Scenario: An agreement with no selection renders the default

- **WHEN** an agreement created with no `(state, type)` is generated as a draft
- **THEN** the stored draft is rendered from the default effective template (unchanged behaviour)

#### Scenario: Regeneration overwrites while unsigned

- **WHEN** a client POSTs `/api/agreements/{id}/document` twice for an agreement that has no signing
  request
- **THEN** both succeed and the later render replaces the stored draft

#### Scenario: Regeneration is blocked once signing has started

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an agreement that already has a signing
  request
- **THEN** the system responds `409 Conflict` and leaves the existing draft unchanged

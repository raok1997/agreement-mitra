## ADDED Requirements

### Requirement: An agreement draft may be system-generated from a template

An agreement's draft PDF SHALL be obtainable **either** by upload (the existing path) **or** by
**system generation** from the rental-agreement template via `POST /api/agreements/{id}/document`.
However the draft is produced, it SHALL be stored under the same object-storage draft key and SHALL
feed the existing stamp and eSign flow unchanged; downstream steps SHALL NOT distinguish an uploaded
draft from a generated one. On success the endpoint SHALL respond `200 OK` and SHALL NOT return the
stored bytes. For an unknown agreement id it SHALL respond `404 Not Found`; a non-UUID id SHALL
respond `400 Bad Request`.

Generating a document SHALL be permitted while the agreement has **no signing request yet**, and
SHALL **overwrite** any prior draft (uploaded or generated). Once a signing request exists for the
agreement (in any state), the draft SHALL be treated as locked and generation SHALL be rejected with
`409 Conflict`, leaving the existing draft unchanged -- the same freeze rule the upload path
enforces, so a document cannot change under an in-flight signature.

#### Scenario: A generated draft feeds signing like an uploaded one

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an existing agreement with no signing
  request
- **THEN** the system renders the rental-agreement template and stores the PDF as that agreement's
  draft under the existing draft key
- **AND** a subsequent signing request finds the generated PDF as the draft and proceeds exactly as
  it would for an uploaded draft

#### Scenario: Regeneration overwrites while unsigned

- **WHEN** a client POSTs `/api/agreements/{id}/document` twice for an agreement that has no signing
  request
- **THEN** both succeed and the later render replaces the stored draft

#### Scenario: Regeneration is blocked once signing has started

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an agreement that already has a signing
  request
- **THEN** the system responds `409 Conflict` and leaves the existing draft unchanged

# agreement-preview Specification

## Purpose

Map a captured agreement onto the rental-agreement template and preview the filled document inline,
on demand, from the capture screen -- rendered fresh, not stored, non-cacheable, with 404 for an
unknown agreement and no party PII in logs. Depends on the `document-rendering` capability (the
`documents` module's `DocumentRenderer`); the agreement-to-template-data mapping lives in the
`signing` module. (Created by archiving change `agreement-preview` (CR-3b).)

## Requirements

### Requirement: Preview the filled agreement document

The system SHALL provide preview of the filled rental-agreement document rendered on demand and **not
stored**. It SHALL continue to serve the id-bound `GET /api/agreements/{id}/preview` for a persisted
agreement (inline PDF, `Cache-Control: no-store`, 404 for an unknown id, 400 for a non-UUID id), and it
SHALL serve a **stateless** preview (`POST /api/templates/document/preview`) that renders an in-progress
working-set data map with no persisted agreement. The prior stateless `POST /api/agreements/preview`
placeholder SHALL be **removed** (superseded by the new route). Both previews SHALL source their HTML
from the **same `TemplateCompiler`** that produces the signed PDF (parity), SHALL be served
`Cache-Control: no-store`, and SHALL NOT write the rendered bytes or the composed party details to any
log.

#### Scenario: The id-bound preview still returns the filled document inline

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an existing agreement
- **THEN** the system responds `200 OK` with an inline `application/pdf` body that composes the
  agreement's parties, property, money, and tenancy dates, sourced from the compiler, and persists
  nothing

#### Scenario: The stateless preview renders an unsaved working set

- **WHEN** a client POSTs an in-progress data map to `/api/templates/document/preview`
- **THEN** the system renders the document from that data with `Cache-Control: no-store`, persists
  nothing, and shows placeholders for any missing fields

#### Scenario: The old stateless preview route is gone

- **WHEN** a client POSTs to `/api/agreements/preview`
- **THEN** the route no longer exists (it is superseded by `/api/templates/document/preview`)

#### Scenario: Preview of an unknown agreement is not found

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an id that does not exist
- **THEN** the system responds `404 Not Found`

### Requirement: Preview responses are non-cacheable and leave no PII in logs

Preview responses SHALL be served with caching disabled (`Cache-Control: no-store`) so
intermediaries do not retain the party PII in the document. The system SHALL NOT write the rendered
PDF bytes or the composed party details to any log at any level on the preview path.

#### Scenario: Preview is marked non-cacheable

- **WHEN** the system responds to a preview request
- **THEN** the response carries `Cache-Control: no-store`

#### Scenario: A preview leaves no PII in logs

- **WHEN** a preview render runs
- **THEN** no log line contains the rendered PDF bytes or the composed party details

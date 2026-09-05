## MODIFIED Requirements

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

The id-bound preview SHALL render the agreement's **selected effective template**: when the agreement
carries a selected template, it SHALL resolve that template's `(state, type)` dimensions; when it
carries no selection, it SHALL render the **default** effective template. Because the id-bound preview
and the generate-as-draft render both resolve the agreement's selected effective template through the
same compiler, the previewed document SHALL be the document that is generated and signed -- parity
holds for a non-default (e.g. Telangana) template, not only the default.

#### Scenario: The id-bound preview still returns the filled document inline

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an existing agreement
- **THEN** the system responds `200 OK` with an inline `application/pdf` body that composes the
  agreement's parties, property, money, and tenancy dates, sourced from the compiler, and persists
  nothing

#### Scenario: The id-bound preview renders the agreement's selected template

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an agreement created with `(TG,
  residential)`
- **THEN** the preview renders the Telangana effective template -- the same template the
  generate-as-draft step renders and pins (parity for the non-default template)

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

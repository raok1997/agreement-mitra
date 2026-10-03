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

Where the agreement has **no selected template** (a bring-your-own agreement), the id-bound preview
SHALL serve that agreement's **stored composed draft** inline instead of rendering from a template, so
that one review surface serves both kinds of agreement. It SHALL NOT render an unrelated template, and
it SHALL NOT fall back to any default template. The stored-draft response SHALL carry the same
`Cache-Control: no-store` and the same server-set content type as a rendered preview, and SHALL NOT
carry the client-supplied filename. Where such an agreement has no stored draft yet, the preview SHALL
respond `404 Not Found` as `application/problem+json`.

The stateless preview remains template-only: it renders a working-set map, and a bring-your-own
document has no working set to render.

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

#### Scenario: Preview of a bring-your-own agreement serves its stored draft

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an agreement that has no selected template
  and has a stored composed draft
- **THEN** the system responds `200 OK` with that stored draft inline, `Cache-Control: no-store`, and a
  server-set content type
- **AND** no template is rendered

#### Scenario: A bring-your-own agreement with no draft yet is not found

- **WHEN** a client GETs the preview of a bring-your-own agreement before any document has been
  uploaded
- **THEN** the system responds `404 Not Found` as `application/problem+json` rather than rendering a
  default template

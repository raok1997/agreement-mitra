## ADDED Requirements

### Requirement: Preview the filled agreement document

The system SHALL provide `GET /api/agreements/{id}/preview` that renders the agreement's data into
the rental-agreement template and returns the result as an **inline PDF**
(`Content-Type: application/pdf`, `Content-Disposition: inline`, shown in the browser). The preview
SHALL be rendered **on demand** and SHALL NOT be stored. The composed document SHALL include the
agreement's parties grouped by role (each with full name, father's name, and current address), the
property, the money (monthly rent, security deposit), and the tenancy dates and duration.

For an unknown agreement id it SHALL respond `404 Not Found` as RFC 9457 `application/problem+json`
(per `api-error-handling`); a syntactically invalid id (not a UUID) SHALL respond `400 Bad Request`.

#### Scenario: Preview returns the filled document inline

- **WHEN** a client GETs `/api/agreements/{id}/preview` for an existing agreement
- **THEN** the system responds `200 OK` with an `application/pdf` body (`Content-Disposition:
  inline`) that begins with the PDF signature and composes the agreement's parties, property, money,
  and tenancy dates into the template
- **AND** nothing is persisted for the agreement (no draft is created by a preview)

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

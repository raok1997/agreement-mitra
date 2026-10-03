## MODIFIED Requirements

### Requirement: Upload a draft agreement PDF

The system SHALL provide `POST /api/agreements/{id}/draft` that accepts a
`multipart/form-data` body carrying exactly one document part -- a PDF or a Word
(`.docx`) document -- and stores the signable draft for an existing agreement. On success it
SHALL respond `200 OK` (or `204 No Content`) and SHALL NOT return the stored bytes.

The endpoint SHALL operate on an **existing** agreement: for an unknown `{id}` it SHALL
respond `404 Not Found` as RFC 9457 `application/problem+json` (per `api-error-handling`);
a syntactically invalid id (not a UUID) SHALL respond `400 Bad Request` as problem+json,
not 404.

The uploaded bytes SHALL be treated as **untrusted**. They SHALL NOT be executed, SHALL NOT
be written to Postgres, and SHALL NOT be logged (verbatim or otherwise). The client-declared
type and filename SHALL NOT be trusted.

The bytes SHALL be **parsed** only to validate them, to convert a Word document to PDF, and
to compose the signable draft, and that parsing SHALL fail closed: a document that cannot be
parsed, is encrypted, has zero pages, cannot be converted, or falls outside the composable
page-count and page-size bounds SHALL be rejected `400 Bad Request` as problem+json with
nothing stored and no parser diagnostic echoed. Parsing is not a relaxation of the
untrusted-bytes rule: the same bytes are already parsed downstream when the stamp
certificate is composited onto them, so validating at upload moves an existing exposure
earlier, to the point where the customer can still act on it.

Conversion of a Word document SHALL run **outside the application process**, in the
rendering service, and that service's outbound network SHALL be denied so that a document
carrying linked or embedded external references cannot reach the network from it.

#### Scenario: Valid PDF is accepted and stored

- **WHEN** a client uploads a valid PDF part for an existing agreement
- **THEN** the system stores the raw bytes in object storage and responds with success
- **AND** the response body does not include the stored bytes

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client uploads a draft for an agreement id that does not exist
- **THEN** the system responds `404 Not Found` as `application/problem+json` and stores
  nothing

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client uploads a draft to `/api/agreements/not-a-uuid/draft`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`, not 404

#### Scenario: An unparseable or encrypted document is rejected

- **WHEN** a client uploads bytes carrying the PDF signature that cannot be parsed, or a
  password-protected document
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and stores
  nothing
- **AND** the response carries no parser diagnostic, no filename and no document content

#### Scenario: Parsed bytes are never executed

- **WHEN** an uploaded document is parsed to validate, convert and compose it
- **THEN** no content it carries is executed
- **AND** the bytes are not written to Postgres and not logged

#### Scenario: Conversion cannot reach the network

- **WHEN** a Word document carrying a linked external reference is converted
- **THEN** the conversion service cannot reach that reference
- **AND** the upload either converts without it or is rejected, never silently fetching it

### Requirement: Draft upload is validated by content and size

The system SHALL reject an upload that is not one of the accepted document formats or that
exceeds the size ceiling, with `400 Bad Request` as RFC 9457 `application/problem+json`, and
SHALL store nothing.

The accepted formats SHALL be **PDF** and **Word (`.docx`)**. The format SHALL be determined
from the file's **content** -- the leading `%PDF-` signature for a PDF, and for a Word
document the ZIP signature together with confirmation that the archive really carries an
OOXML word-processing document -- and SHALL NOT be determined by the client-declared
`Content-Type` or by the filename, both of which are untrusted. A ZIP archive that is not an
OOXML word-processing document SHALL be rejected as an unaccepted format, not attempted.

A part shorter than the shortest accepted signature SHALL be rejected as an unaccepted
format, not error. An empty part SHALL be rejected. The request SHALL carry exactly one file
part; additional or unexpected parts SHALL be rejected. The per-upload size ceiling SHALL be
**10 MiB**, enforced independently of the existing JSON request-body size guard, and an
oversized upload SHALL be rejected before the bytes are stored or converted.

The client-declared part `Content-Type` and the client-supplied **filename** SHALL NOT be
trusted, reflected in any response, or logged.

#### Scenario: Non-PDF content is rejected by magic bytes

- **WHEN** a client uploads a part whose content matches neither the `%PDF-` signature nor
  an OOXML word-processing document (even if it declares an accepted `Content-Type`)
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and stores
  nothing
- **AND** the declared `Content-Type` is what is disbelieved, not what is relied on

#### Scenario: A Word document is accepted on content, not on extension

- **WHEN** a client uploads a `.docx` document under a misleading filename and content type
- **THEN** the system identifies it from its content and accepts it
- **AND** neither the filename nor the declared content type is stored, reflected or logged

#### Scenario: A ZIP that is not a Word document is rejected

- **WHEN** a client uploads a ZIP archive that carries no OOXML word-processing document
- **THEN** the system rejects it `400 Bad Request` as `application/problem+json` without
  attempting conversion

#### Scenario: Empty or sub-signature upload is rejected

- **WHEN** a client uploads an empty part, or a non-empty part shorter than the shortest
  accepted signature
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and stores
  nothing, without an unhandled error

#### Scenario: Oversized upload is rejected before storage

- **WHEN** a client uploads a document exceeding the 10 MiB ceiling
- **THEN** the system responds `400 Bad Request` (problem+json) and stores nothing,
  rejecting before writing to object storage and before any conversion

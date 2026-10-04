## RENAMED Requirements

- FROM: `### Requirement: Signing stub endpoint permitted pending an auth mechanism`
- TO: `### Requirement: Signing-request endpoint restricted to staff`

## MODIFIED Requirements

### Requirement: Signing-request endpoint restricted to staff

The filter chain SHALL require the STAFF authority on the signing-request path (the matcher
`/api/signing/*/request`), and SHALL NOT permit it anonymously. The permit MUST remain scoped to
that path only and MUST NOT use a broad `/api/signing/**` wildcard, so that any future signing
sub-path is denied by default rather than born unauthenticated. Like every unsafe
request, a call to the path SHALL still carry a valid CSRF token, in addition to the STAFF authority.

This replaces the temporary anonymous allowance the requirement previously described, and is the
tightening that allowance itself demanded ("MUST be tightened ... when an authentication mechanism
or real signing logic is introduced"). Both conditions now hold: session authentication and the
STAFF authority exist, and signing is real.

The route is a **retry hatch, not a customer path**. Signing is initiated server-side once the
e-stamp is attached, so no customer request creates a signing request. Making it STAFF-only
therefore removes an anonymous route that egresses signer personal data to the eSign vendor and
draws on paid provider quota, without removing any customer capability -- which is strictly better
than rate-limiting it.

#### Scenario: An anonymous signing request is rejected
- **WHEN** `POST /api/signing/{agreementId}/request` is received without a STAFF session
- **THEN** the security filter chain rejects it with 403, and the request does not reach the
  controller

#### Scenario: Signing-request stub passes the filter
- **WHEN** `POST /api/signing/{agreementId}/request` is received from a caller holding the STAFF
  authority and with a valid CSRF token
- **THEN** the filter chain does not reject it and the request reaches the controller
- **AND** the same request without that authority does not reach the controller
- **AND** the same STAFF request without a valid CSRF token does not reach the controller

#### Scenario: Other signing sub-paths are denied by default
- **WHEN** a request is made to a different `/api/signing/**` path that is not `*/request` (e.g. `GET /api/signing/list`)
- **THEN** the system responds 403 (the wildcard is not open; new sub-paths must be consciously permitted)

#### Scenario: Customer-driven signing is unaffected
- **WHEN** staff attach an e-stamp to a paid agreement
- **THEN** the signing request is created server-side exactly as before, with no anonymous call
  involved

## ADDED Requirements

### Requirement: Role-based authorization for staff operations

The system SHALL carry a **role** on the authenticated principal and SHALL support at least
two roles: **CUSTOMER** (the default for a self-service signup) and **STAFF** (an
AgreementMitra operator). Role SHALL be a server-managed property of the account: it SHALL NOT
be settable by the client at signup, on login, or through any request body or header, and
SHALL NOT be derived from any claim supplied by the external identity provider.

Every newly provisioned account SHALL default to **CUSTOMER**. Granting STAFF SHALL be an
out-of-band administrative action.

Staff-only endpoints SHALL be authorized under the existing **default-deny** posture: the
endpoint is denied unless the caller is authenticated **and** holds the required role. An
unauthenticated caller SHALL receive `401` and an authenticated caller lacking the role SHALL
receive `403`. Authorization SHALL be evaluated **before** any resource lookup, so a refusal
reveals nothing about whether the referenced resource exists.

Role checks SHALL NOT replace ownership checks: an endpoint scoped to a customer's own
resources SHALL still verify ownership even for a STAFF caller, unless a requirement explicitly
grants staff cross-customer access (as `estamp-intake` does for stamp upload).

#### Scenario: New accounts default to CUSTOMER

- **WHEN** a new account is provisioned through the login flow
- **THEN** it is assigned the CUSTOMER role

#### Scenario: Role cannot be self-assigned

- **WHEN** a client supplies a role value in a request body, header, or identity-provider claim
- **THEN** the supplied value is ignored and the server-managed role is unchanged

#### Scenario: Staff-only endpoint denies a customer

- **WHEN** a CUSTOMER-role caller invokes a staff-only endpoint
- **THEN** the response is `403` and no side effect occurs

#### Scenario: Staff-only endpoint denies an anonymous caller

- **WHEN** an unauthenticated caller invokes a staff-only endpoint
- **THEN** the response is `401` and no side effect occurs

#### Scenario: Authorization precedes resource lookup

- **WHEN** an unauthorized caller invokes a staff-only endpoint naming a resource that does
  not exist
- **THEN** the response is the authorization failure (`401`/`403`), not `404`, so the endpoint
  is not an existence oracle

#### Scenario: Default-deny still covers unlisted endpoints

- **WHEN** the role model is introduced
- **THEN** endpoints not explicitly permitted remain denied by default, and the existing
  default-deny verification stays green

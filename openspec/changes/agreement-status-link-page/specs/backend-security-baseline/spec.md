## MODIFIED Requirements

### Requirement: Owner-route authorization for agreements

The security baseline SHALL gate the save and resume routes behind authentication while keeping
anonymous drafting open. `GET /api/agreements` (list mine) and `POST /api/agreements/*/claim`
SHALL require authentication (via the session filter introduced by the `google-oauth-login`
capability).

`PUT /api/agreements/*` (edit) SHALL be `permitAll` in the filter chain, with its
capability/ownership scoping and its freeze decided **in the handler** -- exactly as
`GET /api/agreements/{id}` already is, and for the same reason: the chain cannot see the row's
owner or its stamp state. Being permitted in the chain SHALL NOT be read as being unauthorized;
the handler SHALL refuse an agreement owned by another identity with `404`, and a stamped
agreement with `409`.

Anonymous agreement create (`POST /api/agreements`), draft upload (`POST /api/agreements/*/draft`),
and capability read (`GET /api/agreements/{id}`) SHALL remain `permitAll`.

The authenticated matchers for `GET /api/agreements` and `POST /api/agreements/*/claim` SHALL be
ordered **before** the broader `permitAll` matchers for `/api/agreements/*`, so that list and
claim are gated while anonymous create, capability read, and edit remain reachable. The signing
routes, the HMAC-authenticated webhook, and the `google-oauth-login` filter and handshake permits
SHALL be unchanged. The chain SHALL remain deny-by-default for any unmatched route.

#### Scenario: Anonymous drafting and editing stay open while save and list are gated

- **WHEN** an unauthenticated client calls `POST /api/agreements`, `GET /api/agreements/{id}`, and
  `PUT /api/agreements/{id}` for an unowned agreement
- **THEN** all three are permitted by the chain
- **AND** an unauthenticated `GET /api/agreements` or `POST /api/agreements/{id}/claim` is
  rejected as unauthenticated

#### Scenario: A valid session authenticates the gated routes

- **WHEN** a client calls `GET /api/agreements` or `POST /api/agreements/{id}/claim` with a valid
  `Authorization: Bearer` session
- **THEN** the session filter authenticates the caller and the request is allowed

#### Scenario: Matcher order keeps the capability read and edit open

- **GIVEN** the authenticated `GET /api/agreements` matcher and the `permitAll`
  `/api/agreements/{id}` matchers
- **WHEN** an unauthenticated client GETs or PUTs `/api/agreements/{id}` for an unowned agreement
- **THEN** the request is permitted by the chain (the list matcher does not shadow either)

#### Scenario: Permitting the edit route does not permit the edit

- **GIVEN** an agreement claimed by one identity
- **WHEN** an unauthenticated client PUTs an edit to it
- **THEN** the chain permits the request and the handler responds `404 Not Found`

## ADDED Requirements

### Requirement: The agreement edit route is rate limited per source

The security baseline SHALL apply a per-source rate limit to `PUT /api/agreements/*`,
refusing further attempts beyond the configured limit for a lockout period, because that route
is reachable by an unauthenticated caller holding an agreement id.

The limit SHALL be enforced before the agreement is looked up, so that a throttled response
cannot act as an existence oracle, and SHALL NOT vary in shape according to whether the
agreement exists.

#### Scenario: Excessive edit attempts are refused

- **WHEN** edit attempts from one source exceed the configured rate
- **THEN** further attempts are refused for a lockout period

#### Scenario: Throttling precedes the lookup

- **WHEN** a throttled edit attempt names an agreement that does not exist, and one that does
- **THEN** both responses are identical in shape

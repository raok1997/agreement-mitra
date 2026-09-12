## ADDED Requirements

### Requirement: Owner-route authorization for agreements

The security baseline SHALL gate the save / resume / edit routes behind authentication while keeping
anonymous drafting open. `GET /api/agreements` (list mine), `POST /api/agreements/*/claim`, and
`PUT /api/agreements/*` (edit) SHALL require authentication (via the session filter introduced by the
`google-oauth-login` capability). Anonymous agreement create (`POST /api/agreements`), draft upload
(`POST /api/agreements/*/draft`), and capability read (`GET /api/agreements/{id}`) SHALL remain
`permitAll`; owner-scoping for a claimed agreement's read is enforced in the handler, not the filter chain.

The authenticated matchers for `GET /api/agreements`, `POST /api/agreements/*/claim`, and
`PUT /api/agreements/*` SHALL be ordered **before** the broader `permitAll` matchers for
`/api/agreements/*`, so that list, claim, and edit are gated while anonymous create and capability read
remain open. The signing routes, the HMAC-authenticated webhook, and the `google-oauth-login` filter and
handshake permits SHALL be unchanged. The chain SHALL remain deny-by-default for any unmatched route.

#### Scenario: Anonymous drafting stays open while save, list, and edit are gated

- **WHEN** an unauthenticated client calls `POST /api/agreements` and `GET /api/agreements/{id}` for an
  unowned agreement
- **THEN** both are permitted
- **AND** an unauthenticated `GET /api/agreements`, `POST /api/agreements/{id}/claim`, or
  `PUT /api/agreements/{id}` is rejected as unauthenticated

#### Scenario: A valid session authenticates the gated routes

- **WHEN** a client calls `GET /api/agreements`, `POST /api/agreements/{id}/claim`, or
  `PUT /api/agreements/{id}` with a valid `Authorization: Bearer` session
- **THEN** the session filter authenticates the caller and the request is allowed

#### Scenario: Matcher order keeps the capability read open

- **GIVEN** the authenticated `GET /api/agreements` matcher and the `permitAll`
  `GET /api/agreements/{id}` matcher
- **WHEN** an unauthenticated client GETs `/api/agreements/{id}` for an unowned agreement
- **THEN** the request is permitted (the list matcher does not shadow the capability read)

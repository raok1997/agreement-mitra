## ADDED Requirements

### Requirement: Session authentication filter and login-handshake authorization

The security baseline SHALL gain a session-authentication filter while leaving every existing route
authorization unchanged. The filter SHALL authenticate a request from an opaque
`Authorization: Bearer` session value (per the `google-oauth-login` capability) and SHALL be a no-op
when the header is absent or invalid, leaving the request unauthenticated for the deny-by-default
chain to handle.

The Google login handshake and session exchange SHALL be reachable without a session:
`GET /api/auth/google/start`, `GET /api/auth/google/callback`, and
`POST /api/auth/session/exchange` SHALL be `permitAll`. `GET /api/auth/me` and
`POST /api/auth/logout` SHALL require authentication.

All existing route authorization SHALL be unchanged by this capability: anonymous agreement create,
draft upload, capability read (`GET /api/agreements/{id}`), the signing routes, and the
HMAC-authenticated webhook remain exactly as before (agreement-route gating is introduced by a
separate change). The chain SHALL remain deny-by-default for any unmatched route, and the actuator
lockdown SHALL be unchanged.

#### Scenario: The login handshake is reachable without a session

- **WHEN** an unauthenticated client calls `GET /api/auth/google/start` or
  `POST /api/auth/session/exchange`
- **THEN** the chain permits the request (no session is required to log in)

#### Scenario: me and logout require a session

- **WHEN** an unauthenticated client calls `GET /api/auth/me` or `POST /api/auth/logout`
- **THEN** the request is rejected as unauthenticated
- **AND** with a valid `Authorization: Bearer` session the filter authenticates the caller and the
  request is allowed

#### Scenario: Existing routes are not regressed

- **WHEN** an unauthenticated client calls `POST /api/agreements` or `GET /api/agreements/{id}` for an
  existing agreement
- **THEN** the request is permitted exactly as before this change

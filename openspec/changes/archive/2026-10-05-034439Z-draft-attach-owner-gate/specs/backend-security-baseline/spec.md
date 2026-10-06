## ADDED Requirements

### Requirement: The drafting surface is owner-scoped once an agreement is claimed

The system SHALL answer `POST /api/agreements/{id}/document` (generate), `POST /api/agreements/{id}/draft` (draft upload), `GET /api/agreements/{id}/preview` and `POST /api/agreements/{id}/finalise` for an **unclaimed** agreement to any caller presenting its id, and for a **claimed** agreement only to its owner. This extends "Owner-route authorization for agreements", whose `permitAll` filter-chain posture for these routes is unchanged: the owner check is made in the request path below the filter chain, because only that path can see the row's owner, as for `GET /api/agreements/{id}`.

Any other caller (anonymous, or authenticated as a different identity, including STAFF) SHALL receive the same `404 Not Found` problem response as for an unknown id, with the same problem type, title and detail, so that ownership cannot be probed through these routes. A request refused by the owner check SHALL make no write after the refusal: no draft is stored or replaced, no template pin is written, no signing request is created, and the agreement's last-edited time is unchanged. Every write a generate request makes, the template pin included, SHALL re-check the owner under the same row lock that a claim takes. A claim that commits partway through a link holder's generate request therefore stops that request's remaining writes and can never be overwritten by them.

The owner check SHALL be evaluated before every refusal that depends on the agreement's existence or state: the draft freeze (`409`), the closed-agreement refusal, the no-draft-to-finalise refusal and the jurisdiction gate. It SHALL also be evaluated before any document is rendered. Refusals that depend only on the request itself are answered identically for every agreement id, reveal nothing about a particular agreement, and MAY precede the owner check. These include the non-UUID `400`, CSRF, request-size limits, the multipart part-count check, checks on the content of uploaded bytes, and rate limiting.

The rule "unowned, or owned by the caller" SHALL be defined once, on the agreement aggregate. Every route that applies it SHALL use that one definition: the drafting surface, the capability read, the contacts route, the payment surface, the stamp quote, signing progress and the signed-document download.

#### Scenario: An unclaimed agreement stays open to any link holder

- **GIVEN** an agreement nobody has claimed
- **WHEN** an anonymous caller generates its draft, uploads a draft, previews it and finally finalises it
- **THEN** each request is served as before

#### Scenario: The owner of a claimed agreement is served

- **GIVEN** an agreement claimed by identity A
- **WHEN** A, with a valid session, generates, uploads, previews or finalises it
- **THEN** each request is served as before, and A's finalise returns the same tracking reference and status as it did before this change

#### Scenario: A non-owner gets the unknown-agreement 404 and changes nothing

- **GIVEN** an agreement claimed by identity A, with a stored draft
- **WHEN** an anonymous caller, identity B with a valid session, or a STAFF session generates, uploads, previews or finalises it
- **THEN** each request is refused `404 Not Found` with the same problem type, title and detail as a request for an unknown id
- **AND** the stored draft, its template pin and the last-edited time are unchanged, and no signing request exists

#### Scenario: A non-owner cannot learn a claimed agreement's state from the status code

- **GIVEN** an agreement claimed by identity A whose order has already been placed
- **WHEN** an anonymous caller or identity B uploads a valid PDF, generates, previews or finalises it
- **THEN** each request is refused `404 Not Found`, never `409`

#### Scenario: A write racing a claim cannot land on the claimed agreement

- **GIVEN** an unclaimed agreement
- **WHEN** an anonymous upload, or the template-pin write of an anonymous generate, races a claim by identity A
- **THEN** the write either completes before the claim takes effect, or is refused `404`
- **AND** the claim, and any edit A makes after it, is never overwritten by that write

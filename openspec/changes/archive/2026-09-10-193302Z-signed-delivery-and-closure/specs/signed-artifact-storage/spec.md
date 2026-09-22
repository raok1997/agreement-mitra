## ADDED Requirements

### Requirement: A party can retrieve their own signed agreement

The system SHALL let an authenticated party retrieve the **signed agreement** for an agreement
they are a party to, and SHALL let STAFF retrieve it for support purposes.

Retrieval SHALL be **mediated by the application**: the bytes SHALL be streamed from the
private bucket after an authorization check. The bucket SHALL remain private, and the system
SHALL NOT issue a public URL, nor a long-lived or guessable one, as the retrieval mechanism.

Authorization SHALL be evaluated **before** the agreement is looked up, so the endpoint is not
an existence oracle: a caller with no relationship to an agreement SHALL receive the same
response whether or not it exists.

The **audit trail** SHALL NOT be retrievable through this party-facing path. It remains an
internal evidentiary artifact, produced on request.

Retrieval SHALL remain available **indefinitely**, including after the agreement closes.

#### Scenario: A party retrieves their signed agreement

- **WHEN** an authenticated party requests the signed agreement for an agreement they are a
  party to
- **THEN** the signed PDF is streamed to them from the private bucket

#### Scenario: An unrelated caller is refused without disclosure

- **WHEN** a caller with no relationship to an agreement requests its signed document, and
  again for an agreement id that does not exist
- **THEN** both responses are the same refusal, revealing nothing about existence

#### Scenario: No public or long-lived URL is issued

- **WHEN** the signed document is retrieved
- **THEN** the bucket remains private and the mechanism is an authenticated application
  request, not a public, long-lived, or guessable URL

#### Scenario: The audit trail is not party-retrievable

- **WHEN** a party attempts to retrieve the audit trail through the party-facing path
- **THEN** the request is refused

#### Scenario: Retrieval survives closure

- **WHEN** a party requests their signed agreement after the agreement has closed
- **THEN** the document is still returned

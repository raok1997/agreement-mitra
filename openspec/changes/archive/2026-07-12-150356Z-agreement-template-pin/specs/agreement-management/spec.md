## MODIFIED Requirements

### Requirement: Generate-as-draft pins the effective-template identity for reproducibility

The system SHALL, when generating and storing the signable draft (generate-as-draft), render the
document definition-driven (projection **generate** mode -- full validation) and **pin** the effective
template's identity onto the agreement: its `contentHash` and the resolved **layer versions**
(`layerId -> version`). The pin SHALL be server-managed (never client-settable) and set only after a
successful full render and draft storage. A stored/signed draft SHALL therefore be reproducible from
its pin and SHALL NOT be silently re-rendered against newer layers. A **stateless preview** SHALL NOT
pin anything. The pin SHALL reuse the existing generate-as-draft transition and SHALL introduce no new
signing-status FSM state; the draft-freeze conflict (a signing request already exists) SHALL be
unchanged.

#### Scenario: Generate-as-draft records the pin

- **WHEN** a client invokes generate-as-draft for an agreement and the full render + draft storage
  succeed
- **THEN** the system records the effective template's `contentHash` and layer versions on the
  agreement, alongside the stored draft PDF

#### Scenario: A stateless preview records no pin

- **WHEN** a client requests a stateless document preview
- **THEN** no template identity is pinned and nothing is persisted

#### Scenario: The pin is server-managed only

- **WHEN** an agreement is created or updated through any client-facing request body
- **THEN** the pin columns cannot be set by the client and remain null until generate-as-draft records
  them

#### Scenario: The migration applies forward-only and validates

- **WHEN** the application starts with `V9__agreement_template_pin.sql` present and JPA `ddl-auto:
  validate`
- **THEN** the migration adds the two nullable pin columns without editing prior migrations and the
  `Agreement` mapping validates against them

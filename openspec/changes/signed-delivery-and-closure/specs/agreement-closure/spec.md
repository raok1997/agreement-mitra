## Purpose

The terminal fulfilment state of an agreement: what marks the work finished, what closure
guarantees about the outcome, and what remains available to the parties afterwards.

## ADDED Requirements

### Requirement: An agreement closes when it is signed and delivered to every party

An agreement SHALL reach a terminal **fulfilment** state of `CLOSED` when signing has completed
successfully **and** the signed document has been delivered to **every** party.

Closure SHALL be evaluated automatically; it SHALL NOT require a manual step on the happy path.
Partial delivery SHALL NOT close an agreement - an agreement delivered to one party but not the
other has outstanding work and SHALL remain open.

Closure SHALL record **when** it closed and **why** (completed, or the abandonment reason).

#### Scenario: Signed and fully delivered closes the agreement

- **WHEN** signing has completed and the signed document has been delivered to every party
- **THEN** the agreement becomes `CLOSED` with reason "completed", and the closure time is
  recorded

#### Scenario: Partial delivery does not close

- **WHEN** the signed document has reached one party but delivery to another is pending or
  failed
- **THEN** the agreement remains open

#### Scenario: Closure is automatic

- **WHEN** the final outstanding delivery succeeds
- **THEN** the agreement closes without a manual action

### Requirement: Terminally failed agreements also close, as abandoned

An agreement whose signing reached a terminal failure - rejected, expired, or unable to be
stamped - SHALL also be closable, with a reason distinguishing it from a completed agreement.

This exists so that dead work does not accumulate indefinitely in the staff queue. An
agreement that can never complete SHALL NOT remain indistinguishable from one still in
progress.

Closure as abandoned SHALL be **distinguishable** from closure as completed in every read and
report - the two SHALL NOT be collapsed into a single "closed" fact, because one produced a
signed agreement and the other did not.

#### Scenario: A failed signing can be closed as abandoned

- **WHEN** signing reaches a terminal failure state
- **THEN** the agreement can be closed with an abandonment reason recorded

#### Scenario: Abandoned is distinguishable from completed

- **WHEN** closed agreements are read or reported
- **THEN** those closed as completed are distinguishable from those closed as abandoned

#### Scenario: Dead work leaves the active queue

- **WHEN** an agreement is closed as abandoned
- **THEN** it no longer appears among agreements with outstanding work

### Requirement: Closure is terminal, and does not withdraw access

`CLOSED` SHALL be **terminal**: a closed agreement SHALL NOT return to an in-progress state,
and no further fulfilment action SHALL be taken on it. An attempt to advance a closed agreement
SHALL be refused.

Closure SHALL NOT remove or restrict access to the agreement's documents. A party SHALL be able
to retrieve their signed agreement **indefinitely** after closure. Closure means "no work
outstanding", not "no longer available", and SHALL NOT be treated as archival, retention
expiry, or deletion.

Reopening a closed agreement for revision remains out of scope; a superseding agreement is a
separate instrument.

#### Scenario: A closed agreement cannot be advanced

- **WHEN** a fulfilment action is attempted on a closed agreement
- **THEN** it is refused and the agreement stays closed

#### Scenario: Documents remain retrievable after closure

- **WHEN** a party retrieves their signed agreement long after closure
- **THEN** the document is still available to them

#### Scenario: Closure is not deletion

- **WHEN** an agreement closes
- **THEN** its stored artifacts and records are retained, and nothing is deleted or expired as
  a consequence of closing

### Requirement: Fulfilment state is separate from signing state

Closure SHALL be recorded as **fulfilment** state on the agreement, alongside payment state -
**not** as a state of the signing request. The signing status FSM SHALL be unchanged by this
capability: `SIGNED` SHALL remain terminal for signing, and no new signing state SHALL be
introduced.

The two answer different questions and SHALL remain separately readable: the signing request
answers "what happened to the signatures", and the agreement's fulfilment state answers "is
there work outstanding". An agreement SHALL be able to be signed but not yet closed.

#### Scenario: Signing state is untouched by closure

- **WHEN** an agreement closes
- **THEN** its signing request remains `SIGNED` and no new signing state exists

#### Scenario: Signed but not closed is representable

- **WHEN** signing has completed but delivery is still outstanding
- **THEN** the signing request reads `SIGNED` while the agreement is not yet closed

#### Scenario: Both states are readable

- **WHEN** an agreement's progress is read
- **THEN** the signing outcome and the fulfilment state are both available and distinct

## ADDED Requirements

### Requirement: A scheduled job reconciles missed and incomplete completions

The system SHALL run a scheduled reconciliation job that recovers signing requests whose
completion the webhook did not finish — because a webhook was never delivered, or because
its Details-API read failed and was acked-and-deferred (per `signing-request`), or because
the artifact download failed after the SIGNED transition. The job SHALL drive each
selected request through the **same** status-read → per-invitee-aggregation →
download-on-`SIGNED` code path the webhook uses; it SHALL NOT reimplement that logic.

The job's selection SHALL be **bounded and ordered**: it SHALL select non-terminal
`SIGN_REQUESTED` rows older than a configurable age threshold, plus `SIGNED` rows whose
artifact keys are still null (failed downloads) past a short configurable **grace window**
(so it does not race an in-flight webhook download of a just-completed row); it SHALL order
oldest-first and process at most a configurable batch size per run (no starvation of older
rows). The schedule interval, age threshold, grace window, and batch size SHALL be
configuration. The job SHALL NOT select `PDF_GENERATED` create-path orphans — those have no
provider document id and cannot be Details-reconciled (out of scope; a separate recovery
mechanism). The job SHALL be safe to run repeatedly: because the underlying path is
idempotent (terminal transitions are no-ops, artifact keys are deterministic), a re-scan
SHALL NOT corrupt state or duplicate artifacts. In the test profile the scheduler SHALL be
disabled (or its interval overridden) so it does not fire against unrelated test fixtures;
reconciliation behaviour SHALL be tested by invoking the job method directly.

#### Scenario: A missed webhook is reconciled to its terminal state

- **WHEN** a `SIGN_REQUESTED` request older than the age threshold has in fact completed
  at the provider but no webhook drove it
- **THEN** the scheduled job reads authoritative per-invitee status, aggregates it, and
  transitions the request to its terminal state via the shared completion path

#### Scenario: A failed artifact download is retried

- **WHEN** a `SIGNED` request has null artifact keys (an earlier download failed)
- **THEN** the scheduled job re-attempts the download and records the artifact keys

#### Scenario: The scan is bounded and ordered per run

- **WHEN** more recoverable rows exist than the configured batch size
- **THEN** the job processes at most the batch size in one run, oldest-first, and leaves
  the remainder for a later run (no starvation)

#### Scenario: PDF_GENERATED orphans are not selected

- **WHEN** a `PDF_GENERATED` row (provider failed at create, no document id) exists
- **THEN** the reconciliation job does not select it (it cannot be Details-reconciled)

#### Scenario: Still-in-flight requests are left untouched

- **WHEN** a selected `SIGN_REQUESTED` request is still genuinely in flight (not all
  invitees signed, none rejected/expired)
- **THEN** the job applies no transition and raises no error (a safe no-op)

#### Scenario: Reconciliation reuses the webhook completion path

- **WHEN** the reconciliation job resolves a completion
- **THEN** it uses the same status-read → aggregate → download-on-SIGNED method as the
  webhook handler, not a parallel implementation

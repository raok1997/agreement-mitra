## Context

`delete-draft-agreement` (archived 2026-10-05) gave owners `DELETE /api/agreements/{id}` and built every piece a
purge needs: one deletability rule (`Agreement.isDeletableDraft`), a delete under a full `FOR UPDATE` lock
(`AgreementRepository.findByIdForDelete`), party cascade, `stamp_intake_audit` link cleared by FK
`ON DELETE SET NULL` (V23), a PII-free `agreement_deletion` record, and after-commit best-effort removal of
`DraftService.draftStageKeys(id)`. What it deliberately left out: unclaimed drafts (the endpoint is owner-only),
any time-based removal, and orphaned objects from a failed after-commit removal — all assigned to register row
`anonymous-draft-retain-and-purge`.

Constraints from the code as it is today:
- `agreement_deletion.owner_identity_id` is `NOT NULL`, and the entity copies `agreement.ownerIdentityId()`.
- `BlobStore` has `put`/`get`/`delete` only; `MinioBlobStore` is its single adapter.
- `drafts/{id}.pdf` is not only draft-stage data: for a paid agreement awaiting stamping it is the instrument
  staff stamp (`StampIntakeService` reads it when there is no re-render). Removing one that belongs to a live
  agreement is a correctness failure, not just data loss.
- `lastEditedAt` is moved only by content edits (terms, parties, contacts, draft attach) — never by claim,
  reads, payment or signing progress (agreement-management "An agreement records when its content was last
  edited"). That is the clock the user chose.
- `recovery_audit.agreement_id` is set only for paid/waived agreements, `stamp_quote` only exists with a
  payment order, and `signed_document_delivery` only after signing — none can reference a deletable draft.
- `@EnableScheduling` (`signingrequest/SchedulingConfig`) runs on Spring Boot's default scheduler pool of **one
  thread**, shared today by `SigningReconciliationJob`, `PaymentReconciliationJob` and `DeliveryRetryJob` — the
  fallbacks for missed webhooks.

## Goals / Non-Goals

**Goals:** remove stale unpaid drafts (claimed or not) after 90 days without an edit, through the same code as
an owner's delete; sweep orphaned `drafts/` objects left by a failed delete; state the period in terms §10 and
the privacy policy.

**Non-Goals:** pre-purge warning email; any retention for paid/signed/closed agreements (terms §12, counsel);
retention of `agreement_deletion` rows themselves; a per-environment period; frontend UI (e.g. "expires in"
badges on My agreements); a guard against a host clock that jumps forward (the per-run cap bounds the damage).

## Decisions

All new classes live in `in.agreementmitra.signing.agreement`, package-private: `DraftRetention` needs the
package-private `AgreementRepository`, and `DraftRetentionJob` calls `DraftRetention`. (Unlike
`DeliveryRetryJob`, which sits in `signing.delivery`.)

**D1 — One delete core, two entry points.** Extract the body of `DraftService.deleteDraft` after the owner
check into a private `remove(Agreement, DeletionReason)`: repository delete, deletion record stamped with
`Instant.now()` at that moment, after-commit object removal. `deleteDraft` keeps its owner filter, 404 and 409.
A new **package-private** `@Transactional boolean purgeIfStale(UUID id, Instant cutoff)` loads the row with
`findByIdForPurge` (D2b), returns `false` unless the row exists, `lastEditedAt` is before `cutoff`, and
`isDeletableDraft(...)` holds, and otherwise calls `remove(..., RETENTION_PURGE)`. Package-private because
`DraftService` is Java-public for the `api` controller; nothing outside the package may purge with an arbitrary
cutoff. *Alternative:* a separate service with its own delete — rejected, the keys list and the record shape
would drift.

**D2 — Candidate query is a deliberate pre-filter copy of the rule.**
`AgreementRepository.findStaleDraftCandidates(cutoff, afterEditedAt, afterId, limit)` — native: `payment_state
= 'UNPAID' AND closure_state = 'OPEN' AND last_edited_at < :cutoff AND NOT EXISTS (signing_request) AND NOT
EXISTS (payment_order) AND (last_edited_at, id) > (:afterEditedAt, :afterId)`, ordered by `(last_edited_at,
id)`, limited; returns `(id, last_edited_at)`. Filtering children in SQL keeps a stale-but-finalised agreement
from being re-selected every day. This keeps `'UNPAID'`/`'OPEN'` as SQL literals so the planner matches the V25 partial index. It restates
`isDeletableDraft` and reads `signing_request`/`payment_order`
directly instead of through `SigningRequestQuery`/`PaymentOrderQuery` — accepted because it is only a
pre-filter: the decision is re-made in Java under the lock, so drift can never cause a wrong delete, and the
keyset cursor (D3) means drift cannot cause a loop either. `isDeletableDraft`'s javadoc gains "the purge
candidate query restates this rule; change both". Integration test 4.4 asserts that every id the query returns
over the fixture set passes the lock-time check (the pin that keeps the copies in step).

**D2b — Lock with `SKIP LOCKED`.** `findByIdForPurge`: `SELECT * FROM agreement WHERE id = :id FOR UPDATE SKIP
LOCKED`. A row another transaction holds (an edit, claim, draft attach, finalise, payment, owner delete,
another instance's purge, or an in-flight `signing_request`/`payment_order` insert holding `FOR KEY SHARE`) is
skipped for this run and re-evaluated tomorrow; the purge never waits on a user request and parallel
instances do not queue on each other. A row edited by a transaction that committed before the lock is re-read
fresh and kept by the `lastEditedAt` check. Checkout does **not** lock the agreement before its
`payment_order` insert (`PaymentOrderService` quotes and calls Razorpay first), so a purge that commits in that
window makes the insert fail its FK and the customer sees an error with an unpaid, rowless Razorpay order.
Accepted: it needs a checkout started on a draft unedited for 90 days, inside a millisecond window once a night.

**D3 — One transaction per agreement, keyset-paginated, capped.** `DraftRetention.run(Instant now)` is **not**
`@Transactional` (so each `purgeIfStale` call through the `DraftService` proxy commits on its own and its
after-commit removal fires immediately). It pages candidates 100 at a time with a keyset cursor — starting at `(Instant.EPOCH, new UUID(0, 0))`,
never NULL (a NULL row comparison returns no rows) — advanced past
every returned row whatever its outcome, so a row that fails or is skipped is never re-selected in the same
run, and stops when a page is empty or 10 000 candidates have been **attempted**. Per-id `RuntimeException` is
caught, counted as `failed`, logged with the redacted id and the exception class name only. *Alternative:* one
bulk `DELETE … WHERE` — rejected: it skips the record, the object removal and the lock-time re-check.

**D4 — The deletion record gains a reason; owner becomes nullable.** V25: `ALTER COLUMN owner_identity_id DROP
NOT NULL`; `ADD COLUMN reason VARCHAR(16) NOT NULL DEFAULT 'OWNER_DELETE'`, then `DROP DEFAULT`;
`CHECK (reason IN ('OWNER_DELETE','RETENTION_PURGE'))`. A `CHECK (reason = 'RETENTION_PURGE' OR owner_identity_id
IS NOT NULL)` is deliberately **not** added: V23 promises a future account erasure may clear
`owner_identity_id`, and such a CHECK would forbid that; the entity factory guarantees the owner for an owner
delete. V25 also re-issues `COMMENT ON COLUMN stamp_intake_audit.agreement_id` (it currently says only
"deleted by its owner"), adds `COMMENT ON TABLE agreement_deletion` covering both reasons (V23's header is
immutable), and adds the partial index `agreement (last_edited_at, id) WHERE payment_state = 'UNPAID' AND
closure_state = 'OPEN'` so the candidate query stays a range scan. **V26** adds a plain index on `payment_order (agreement_id)` (V17 has only a partial one), which the candidate query's `NOT EXISTS`, the lock-time check and each delete's FK check need; it is a separate migration because V25 had already been applied locally when the index was added. Enum `DeletionReason`,
`@Enumerated(STRING)`; `AgreementDeletion` javadoc updated. *Alternative:* a separate `agreement_purge` table —
rejected, support would look in two places to answer "was this deleted?".

**D5 — Period is a code constant.** `DraftRetention.RETENTION = Duration.ofDays(90)`, javadoc pointing at
terms §10. Not a property: a per-env value would let the backend disagree with the published terms. The two
stacks hold two copies unavoidably, so each side pins its own: a backend unit assertion `RETENTION ==
Duration.ofDays(90)` whose message names terms §10, and a frontend test pinning "90 days" in §10.

**D6 — Orphan sweep removes only on positive evidence.** The sweep lists every prefix of the draft-stage keys,
taken from a new `DraftService.draftStagePrefixes()` defined beside `draftStageKeys` (today only `drafts/`;
`byo-document-upload`, in flight, adds `uploads/` and `converted/` keys and must add their prefixes in the
same place). `BlobStore.list(String prefix)` returns
`List<StoredObject>` — record `(String key, Instant lastModified)` in the `signing` package. The listing is
materialised in memory (accepted: one prefix, small at today's volume); `MinioBlobStore` iterates
`listObjects(prefix, recursive)` and a failure on the call **or any item** fails the whole listing with an
exception whose message carries no object name. The sweep:
1. maps a key to an id only through `DraftService.draftIdOf(key)`, which takes the 36 characters after the
   prefix as the candidate UUID (prefix- and extension-agnostic) and returns it only if
   `draftStageKeys(id).contains(key)` — so the key format has one definition and a non-canonical
   UUID spelling is rejected;
2. removes an object only when its `lastModified` is more than 24 h old, **no** agreement row has that id,
   **and** an `agreement_deletion` row exists for that id. Every legitimate orphan comes from a delete that
   wrote that record (no delete path existed before V23), so an app pointed at an empty or wrong database, or
   a bucket shared across environments, removes nothing;
3. deletes the exact listed key, never one rebuilt from the id;
4. stops after 1 000 removals per run.
*Alternative:* `existsById` alone — rejected by security review: absence of a row is not evidence of deletion.

**D7 — Job, opt-in.** `DraftRetentionJob` (package-private `@Component`, `@ConditionalOnProperty(prefix =
"signing.draft-retention", name = "enabled", havingValue = "true", matchIfMissing = false)`, `@Scheduled(cron =
"${signing.draft-retention.cron}", zone = "Asia/Kolkata")`, default `0 30 3 * * *` in `application.yml` only, via
`SIGNING_DRAFT_RETENTION_CRON`) calls `DraftRetention.run(
Instant.now())`, which purges and then sweeps, each half in its own `try/catch`. The job wraps the call in a
catch-all. One INFO line: `purged`, `skipped`, `failed`, `purgeAborted`, `objectsRemoved`, `sweepFailed`; a candidate page that cannot be read ends the purge with its partial counts and `purgeAborted=true` (added in validate: a mid-run failure must not read as an empty run). **No log line the
run's own code writes carries a throwable or exception message** (Hibernate/Postgres messages repeat full key
values, and an unclaimed draft's id is its bearer link) — class name only, ids via `AgreementIds.redact`. The
driver's own log line is already kept free of key values by the datasource's `logServerErrorDetail=false`
(`application.yml:21`, `DEPLOYMENT.md`), the shared control the owner delete relies on too.
`application.yml` sets `spring.task.scheduling.pool.size: 4`, one thread per scheduled job, so a long first
run cannot delay the reconciliation fallbacks. **Opt-in, not on by default:** the purge deletes the objects of whatever bucket it is pointed at, so a database
restored or cloned from production and run against a shared bucket (the default `S3_BUCKET` is the same
everywhere) would purge agreements that are live in production and remove their PDFs — and write deletion
records that then satisfy the sweep. So the job runs only where `SIGNING_DRAFT_RETENTION_ENABLED=true` is set
(production), documented in `DEPLOYMENT.md` beside a stated invariant that each environment has its own bucket;
`application-test.yml` additionally sets `enabled: false`. Disabling it in production is an incident-only lever,
since terms §10 promises the deletion. Multi-instance runs are safe without ShedLock (D2b skips locked rows; blob delete is idempotent).

**D8 — Terms and privacy text.** Terms §10's last paragraph becomes: "Once an unpaid draft has gone 90 days
without a change to its content, we delete it within a few days, whether or not it is saved to an account. It
is removed as described above for a draft you delete yourself, and its link stops working. An agreement you
have finalised for signing or taken to payment is not deleted this way." ("as described above" carries
paragraph 2's caveats: backups rotate, emailed copies cannot be recalled; "within a few days" is honest about
the daily run, skipped locked rows and the cap.) A finalised-but-unpaid agreement therefore has no stated
period — that gap belongs to the existing register row `withdraw-unpaid-finalised-agreement`, which this change
re-rates (see 5.6), not to this one. Privacy §8 is renamed "8. Deleted drafts"; the existing record sentence changes "the account that deleted
it" to "the account it was saved to, if any", and gains: "We keep the same record when we delete an unpaid draft
that has gone 90 days without a change." Privacy §7's gap text ("states no period of its own, other than …") gains the 90-day draft period as a
second exception. The privacy test forbids "deleted after"; the wording avoids it.
Regenerate counsel copies with `npm run legal:doc`. `docs/COUNSEL-BRIEF.md` data-protection paragraph (b) gets
one sentence asking whether 90 days is a permissible period for an abandoned unpaid draft.

## Risks / Trade-offs

- [A draft claimed (saved to an account) on day 89 is purged that night] → accepted: claiming is not a content
  edit and does not move `lastEditedAt` by an existing requirement; resetting the clock on claim would need a
  second timestamp for one edge case. The drafter has had 89 days without a change; terms §10 says "whether or
  not it is saved to an account". Revisit if support sees it.
- [A customer returning after 91 days without editing finds the draft gone] → stated in terms §10; pre-purge
  email is a non-goal.
- [A bug in the candidate query selects a live agreement] → pre-filter only; the lock-time re-check uses the
  same `isDeletableDraft` as the owner delete; integration tests cover each kept case and pin query ⊆ rule.
- [First production run meets a backlog] → 10 000-attempt cap, own scheduler thread, every deletion recorded.
- [Wrong or cloned database against a shared bucket] → the job is opt-in and enabled only in production (D7);
  the sweep additionally needs a deletion record (D6). The row purge's own after-commit object removal is not
  protected by D6 — the opt-in is the control for it.

## Migration Plan

V25 is additive and backfills existing rows to `OWNER_DELETE`. Flyway is forward-only; there is no rollback
deploy path. The job is off unless `SIGNING_DRAFT_RETENTION_ENABLED=true`; production sets it at deploy
(`DEPLOYMENT.md`). Unsetting it is an incident-only lever.

## Open Questions

- Counsel: is 90 days a permissible period for an abandoned unpaid draft (appended to COUNSEL-BRIEF (b)). The
  build does not wait on the answer; a different period is a one-constant, one-paragraph change.

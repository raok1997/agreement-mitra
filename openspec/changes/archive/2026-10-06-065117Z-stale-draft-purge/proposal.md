## Why

Unpaid drafts are kept forever. Nothing removes an agreement that was started and abandoned, so the
parties' names, parentage, addresses and contacts typed into it sit in Postgres, and its draft PDF sits in
object storage, indefinitely. That is the wrong default for identity/legal infra, and the exposure grows
with traffic. A second leak feeds the same pile: an owner's draft delete removes `drafts/{id}.pdf` after
commit, best effort, so a failed removal leaves a PII-bearing PDF that no row points to and nothing will
ever clean up. This closes register row `anonymous-draft-retain-and-purge`, one of the triggers the release
must "do or explicitly defer" before launch.

## What Changes

- A **daily retention purge** deletes every unpaid draft whose content has not been edited for **90 days**
  (`lastEditedAt` older than now − 90 days). Scope is every agreement the existing deletability rule
  (`Agreement.isDeletableDraft`: no signing request, no payment order, `UNPAID`, `OPEN`) admits — **both
  unclaimed (anonymous) and claimed drafts**, matching what terms §10 already reserves ("we may delete
  unpaid drafts that have been untouched for a long time").
- The purge removes exactly what an owner's delete removes — the agreement, its parties, its draft-stage
  objects after commit — through the **same delete path**, so the two cannot drift. Each purged draft
  leaves the same PII-free deletion record, marked as a retention purge rather than an owner delete; an
  unclaimed draft's record has no owner.
- **Orphaned draft objects are swept**: the same job lists the `drafts/` prefix and removes an object only
  when its agreement no longer exists **and** a deletion record proves it was deleted, once it is older
  than a 24-hour grace period. Absence of a row alone is never enough (a wrong database or shared bucket
  must remove nothing). `BlobStore` gains a prefix listing for this.
- **Terms §10** states the period: an unpaid draft is deleted once it has gone 90 days without a change,
  and its link stops working. The privacy policy's "drafts you delete" clause says the same record is kept
  for a draft we delete. The 90-day question is added to counsel's existing data-retention question in
  `docs/COUNSEL-BRIEF.md`, not raised as a new item.
- **Migration V25**: `agreement_deletion.owner_identity_id` becomes nullable and the table gains a
  `reason` column (`OWNER_DELETE` | `RETENTION_PURGE`, existing rows backfilled `OWNER_DELETE`).

Non-goals: warning the owner by email before a purge; a purge of paid, signed or closed agreements (terms
§12's three-year horizon is counsel's open question); purging the deletion records themselves; making the
period configurable per environment.

## Capabilities

### New Capabilities
- `draft-retention`: the scheduled purge of stale unpaid drafts and the sweep of orphaned draft objects —
  which agreements qualify, what is removed, what record is kept, and how a racing edit is honoured.

### Modified Capabilities
- `agreement-management`: "An owner may delete an unpaid draft" — the deletion record gains a reason and
  the owner delete's record is the `OWNER_DELETE` kind (the purge shares the record shape).
- `legal-policy-pages`: terms §10 states the 90-day period, and the privacy policy's deleted-drafts clause
  covers a draft we delete.

## Impact

- **Backend** (`signing` module only): `DraftService` (shared delete core + purge entry point),
  `AgreementDeletion` (nullable owner, reason), `AgreementRepository` (stale-draft candidate query), a new
  package-private `DraftRetentionJob` with `@Scheduled` daily run, scheduler pool sized so it cannot delay the reconciliation jobs, `BlobStore` + `MinioBlobStore`
  (`list(prefix)` returning key + last-modified), `application*.yml` (job disabled in the test profile, like
  the delivery retry job). Migrations `V25__draft_retention.sql` and `V26__payment_order_agreement_index.sql`.
- **Frontend**: `frontend/src/content/termsOfService.ts` §10, `privacyPolicy.ts` §8 and their tests;
  regenerated `docs/TERMS-OF-SERVICE.md` / `docs/PRIVACY-POLICY.md` (`npm run legal:doc`).
- **Docs**: `docs/COUNSEL-BRIEF.md` (period question appended), `docs/ROADMAP.md` (register row closed;
  release-trigger list updated; `withdraw-unpaid-finalised-agreement` re-rated — finalised-but-unpaid agreements
  stay outside the purge), `docs/DEPLOYMENT.md` (the job is opt-in: production sets
  `SIGNING_DRAFT_RETENTION_ENABLED=true`; one bucket per environment).
- **Signing FSM**: untouched. The purge acts only on agreements with no signing request, i.e. that never
  left the pre-signing stage; no `SignatureStatus` transition is added or used.
- **PII/security**: no new outbound PII flow, and no Aadhaar/OTP/VID anywhere. The change **removes** PII
  (party rows, draft PDFs). Logs carry only redacted agreement ids (`AgreementIds.redact`) and counts —
  never an object key's raw name beyond the derived id, never a party field. The deletion record holds no
  party data. Sandbox + dummy data only is preserved; no secret is added.

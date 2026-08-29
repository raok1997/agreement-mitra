# Tasks -- signed-delivery-and-closure

Schema first (S1), then the email seam (S2), delivery (S3), closure (S4), party retrieval (S5).
Behavioral change -> unit **and** integration tests (S6), per `dev-policy`.

> **Depends on `zoop-aadhaar-esign`** (the completion path that triggers delivery, and the
> agreement-level fulfilment-state precedent set by payment) and therefore on
> `manual-estamp-upload`.

> **The two things most likely to go wrong.** (1) Emailing a legal document to a **wrong
> address** -- deliver only to signing-verified addresses, never a draft-time one. (2) Emailing
> it **twice** -- the completion path is re-entered by both the webhook and the reconciliation
> job, so exactly-once must be enforced at the recipient record, not assumed from the trigger.

## 1. Schema

- [x] 1.1 New forward-only migration. Do NOT edit any applied migration.
- [x] 1.2 Per-recipient delivery records: signer, artifact, status, attempt count, last error,
  created/sent timestamps, version column for optimistic locking.
- [x] 1.3 Agreement fulfilment closure: closed flag/state, closed-at, and reason (completed vs
  abandoned, distinguishable).
- [x] 1.4 App boots under `ddl-auto: validate`.

## 2. Email seam

- [x] 2.1 Vendor-neutral outbound email seam: recipient, subject, body, optional attachment
  (filename + content type). No provider type crosses it.
- [x] 2.2 Stub implementation capturing messages in memory; **active by default** so tests and
  local runs send nothing.
- [x] 2.3 **SMTP adapter** serving both environments (design D7): host, port, username, and
  password all configuration. Dev = free Zoho Mail; prod = ZeptoMail (`emailapikey` + send
  token). Do NOT write two adapters.
- [x] 2.4 Credentials from env vars only; the production host is never a default. Match the Zoho
  **data centre** (`.in` vs `.com`) to the account or authentication fails confusingly.
- [x] 2.5 Enforce the attachment ceiling before sending, derived from the **assembled message**
  size, not the raw file: ZeptoMail caps a message at 15 MB total including headers and base64
  encoding, so cap the raw PDF around 10 MB (design D7a). Choose this together with the scan
  ceiling in `manual-estamp-upload`.

## 3. Delivery

- [x] 3.1 Trigger delivery from the completion path, after artifacts are stored -- reached by
  **both** the webhook and the reconciliation job.
- [x] 3.2 Resolve each recipient's **signing-verified** address (the address at which that party
  completed signing). Never fall back to a draft-time address.
- [x] 3.3 Unresolvable recipient -> no send, record the condition, surface for staff.
- [x] 3.4 Claim each recipient's delivery in a **guarded transition before sending**, so
  re-entry and concurrency cannot double-send.
- [x] 3.5 Attach the **signed agreement only**. Never attach or link the audit trail.
- [x] 3.6 Retry transient failures with bounded backoff; mark permanent failures (hard bounce,
  rejected address) failed with a reason and escalate.
- [x] 3.7 Oversize document -> notify that the document is available in the app instead; record
  the oversize condition. Never send a truncated attachment.
- [x] 3.8 Delivery must not block or roll back completion; a failing seam leaves artifacts
  stored and completion recorded.
- [x] 3.9 Staff-triggered deliberate re-send, recorded as a separate attributed attempt.
- [x] 3.10 Redact recipient addresses in logs (mask the local part); never log attachment bytes
  or document content.

## 4. Closure

- [x] 4.1 Close the agreement when signing completed **and** every party has been delivered to;
  record closed-at and reason "completed".
- [x] 4.2 Do not close on partial delivery.
- [x] 4.3 Allow closure as **abandoned** for terminal signing failures (`FAILED`, `EXPIRED`,
  `STAMP_FAILED`), with the reason recorded and distinguishable from completed.
- [x] 4.4 Make `CLOSED` terminal: refuse further fulfilment actions on a closed agreement.
- [x] 4.5 Exclude closed agreements from the outstanding-work views (including the staff stamp
  queue).
- [x] 4.6 Confirm the **signing FSM is unchanged** -- no new signing state, `SIGNED` still
  terminal (design D1).

## 5. Party-facing retrieval

- [x] 5.1 Authenticated endpoint streaming the signed PDF from the private bucket after an
  authorization check. No public, long-lived, or guessable URL.
- [x] 5.2 Evaluate authorization **before** the lookup -- refusal must not reveal existence.
- [x] 5.3 Refuse the audit trail on the party-facing path.
- [x] 5.4 Retrieval keeps working indefinitely, including after closure.

## 6. Tests

**Unit:**

- [x] 6.1 Recipient resolution: uses the signing-verified address; a differing draft-time
  address receives nothing; unresolvable recipient escalates rather than guessing.
- [x] 6.2 Exactly-once claim logic: a second attempt on an already-claimed recipient does not
  send.
- [x] 6.3 Retry classification: transient retries with backoff to the bound; permanent stops and
  escalates.
- [x] 6.4 Oversize document falls back to notification; no truncated attachment.
- [x] 6.5 Audit trail is never attached.
- [x] 6.6 Closure predicate: closes only when signed **and** all delivered; abandoned closure
  for each terminal failure state; abandoned distinguishable from completed.
- [x] 6.7 Redaction: addresses masked, no attachment bytes or document content in logs.

**Integration:**

- [x] 6.8 Happy path: completion -> both parties emailed exactly once (stub seam) -> agreement
  `CLOSED` as completed.
- [x] 6.9 **Webhook redelivery after successful delivery sends nothing further.**
- [x] 6.10 **Reconciliation re-entry after delivery sends nothing further.**
- [x] 6.11 Concurrent completions -> each recipient receives exactly one message.
- [x] 6.12 One recipient hard-bounces: the other is still delivered, the agreement does **not**
  close, the signing request remains `SIGNED`, and the failure is visible to staff.
- [x] 6.13 Email seam unavailable at completion -> artifacts still stored, completion recorded,
  delivery pending retry.
- [x] 6.14 Party retrieval: party succeeds; unrelated caller refused identically for existing
  and non-existent agreements; audit trail refused; retrieval still works after closure.
- [x] 6.15 Closed agreement refuses further fulfilment actions and leaves the staff queue.
- [x] 6.16 Schema boots under `ddl-auto: validate`; `ModularityTests` green.

## 7. Docs + housekeeping

- [x] 7.1 Update `docs/DOMAIN-AND-EMAIL-SETUP.md` with the decision: free Zoho Mail for
  development, **ZeptoMail for production**. State plainly that the free mailbox is a
  development-only sender (send caps, no bounce reporting) and must not carry real user traffic.
- [x] 7.2 Document the email env vars, the attachment ceiling, and retry settings.

**Production-readiness (do NOT defer to launch day):**

- [ ] 7.6 **Start the ZeptoMail account review early.** It requires a Customer Validation form
  and typically takes 2-3 business days; production sending is blocked until it passes.
- [ ] 7.7 Configure SPF/DKIM for the sending domain against ZeptoMail, and verify alignment
  before first production send.
- [ ] 7.8 Wire ZeptoMail's **bounce webhook** into the existing permanent-failure path (S3.6).
  Additive; until this exists, `SENT` means "accepted by the provider", not "delivered".
- [x] 7.3 Update `docs/ROADMAP.md` with the completed end-to-end journey.
- [ ] 7.4 **Before deploying anywhere holding real signed agreements**, confirm the deploy will
  not trigger a mass send for pre-existing `SIGNED` agreements (design, Migration Plan step 3).
- [x] 7.5 Run `./gradlew spotlessApply` then the full gate (`./gradlew check`, or
  `./gradlew test spotbugsMain` where `osv-scanner` is unavailable locally).

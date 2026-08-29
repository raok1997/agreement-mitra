## Context

See `proposal.md` - Why, and `docs/integrations/zoop.md` for the vendor facts. The constraints
that shape this design:

- `EsignProvider` is already multi-invitee and vendor-neutral, and eSign v5's `/init` takes a
  `signers[]` array. The seam fits almost exactly; the one place it does not is webhook
  verification, whose current contract explicitly forbids a transport-header parameter.
- v5 authenticates callbacks with a `webhook-security-key` **header** whose value is issued
  **per transaction** at `/init`. Leegality uses a body MAC over the document id with a
  **config-wide** secret. Both must keep working.
- v5 places signatures by `page_num`/`x_coord`/`y_coord`, with `x_coord` measured from the
  **right** edge. We emit `esign:<role>` text anchors.
- `manual-estamp-upload` moves stamping to a staff step and makes an attached stamp a
  precondition of signing. This change depends on it.
- No payment gateway exists, and none is being chosen here.

### Sequence

```
Customer      System                 Staff        ZOOP v5              Signers
   |            |                      |             |                    |
   |- finalise ->|                     |             |                    |
   |            [payment gate: OPTIONAL -> pass]     |                    |
   |            [PDF_GENERATED]        |             |                    |
   |            |<- stamp upload ------|             |                    |
   |            [STAMPED]              |             |                    |
   |            |-- POST /v5/init (signers[2], SEQUENTIAL, send_invite) ->|
   |            |<- group_id, request_id[], webhook_security_key ---------|
   |            [SIGN_REQUESTED]       |             |-- invite email --->|
   |            |                      |             |<-- owner signs ----|
   |            |<-- webhook (header key) -----------|                    |
   |            |-- GET /v5/fetch/group ------------>|   (trigger only;   |
   |            |<-- per-signer statuses ------------|    re-read state)  |
   |            [partial -> no-op]     |             |-- invite email --->|
   |            |                      |             |<-- tenant signs ---|
   |            |<-- webhook -----------------------|                     |
   |            |-- GET /v5/fetch/group ------------>|                    |
   |            [SIGNED] -> store complete_signed_url + audit trail       |
```

The reconciliation job runs the same fetch -> FSM path for missed webhooks.

## Goals / Non-Goals

**Goals:**

- One provider call invites both parties; ZOOP delivers the emails; completion arrives by
  webhook with reconciliation as the fallback.
- Keep Leegality working behind the same seam, selected by configuration.
- Build the payment gate now, permissive, so enabling it later is configuration only.
- Keep every ZOOP specific inside the adapter package.

**Non-Goals:**

- Choosing or integrating a payment gateway.
- ZOOP eStamp, templates, WhatsApp/Email/QuickSign types, DSC, location/photo capture.
- Replacing the reconciliation job or the FSM aggregation rule - both are reused unchanged.
- A staff console UI.

## Decisions

### D1: Webhook verification takes headers, and splits into parse-then-verify

Change `verifyWebhook(String payload)` to receive the request headers as well, and allow
verification to be expressed in two steps: the adapter parses the untrusted transaction id from
the body; the module loads that transaction's stored key; the adapter compares.

*Why:* a per-transaction secret cannot be checked from configuration, and the adapter must not
gain repository access - that would put persistence inside the vendor boundary. The module
already owns the lookup; it just needs to hand the secret back.

*Alternative rejected:* give the adapter a repository or a `WebhookSecretStore` port. It works
but inverts the dependency the seam exists to prevent, and makes the adapter stateful.

*Alternative rejected:* keep one config-wide secret and ignore ZOOP's per-transaction key. That
discards the only authentication the vendor offers.

*Note:* this is a **breaking** interface change, so the Leegality adapter is updated in the
same change. Its body-MAC path simply ignores the headers.

### D2: The webhook stays a trigger; this is now load-bearing

Continue to ignore the webhook body entirely and re-read state from `/v5/fetch/group`.

*Why:* ZOOP's header key proves the caller **holds the key** and binds nothing to the payload -
unlike an HMAC over the document id. Anyone with the key could submit an arbitrary body. Our
existing discipline neutralises that completely, but only because we never trust the body.
Previously a defensive nicety; now the actual control. It must not be "optimised away" later by
reading a status straight from the payload.

### D3: Persist the per-transaction key encrypted, compare in constant time

*Why:* it is a credential that authenticates inbound state changes for the life of the
transaction. A database read should not yield a working webhook credential.

*Note:* constant-time comparison matters more here than for the MAC, since the value is a
directly-presented secret rather than a derived digest.

### D4: Anchor-to-coordinate mapping lives in the adapter, and is verified visually

Use a PDFBox `PDFTextStripper` subclass over the **stamped** PDF to locate each `esign:<role>`
anchor, then convert to v5 coordinates - mirroring x (`page_width - x`) for ZOOP's
right-edge origin.

*Why:* placement is a vendor detail. `documents` already emits a stable, non-PII anchor and
must not learn about ZOOP. Running it on the stamped PDF is also automatically correct: the
prepended certificate page shifts page numbers, and searching the final document handles that
with no offset arithmetic.

*Why visually verified:* a wrong origin produces confidently wrong output with no error. A unit
test asserting our own arithmetic would pass while signatures land in the margin. The
acceptance check is a rendered document, which is why it is a manual-test gate rather than only
an assertion.

*Fail closed:* a missing anchor refuses the request. Signing at a guessed default position on a
legal instrument is worse than not signing.

### D5: Payment gate as a mode, not a feature flag on a missing feature

Model payment state (`UNPAID` / `PAID` / `WAIVED`) and a gate mode (`OPTIONAL` / `REQUIRED`),
with `REQUIRED` fully specified and tested now even though it will run `OPTIONAL`.

*Why:* the expensive part of adding payment later is not the gateway call, it is discovering
where the pipeline should have blocked. Specifying and testing `REQUIRED` now settles that
while the pipeline is being built. Onboarding a gateway becomes an adapter plus a config flip.

*Why `WAIVED` is separate from `PAID`:* collapsing them destroys the ability to answer "how much
money came in" from the same field that answers "may this proceed". They are different facts.

*Alternative rejected:* a boolean `paid` flag. Cannot express a deliberate waiver, and offers
no place for the audit of who allowed it.

### D6: The gate sits before stamp intake, not only before signing

*Why:* stamp intake is where **real money leaves** - staff buy an SHCIL certificate. Gating only
at signing would let unpaid orders consume stamp duty. This also closes the consequence recorded
in `manual-estamp-upload` design D8, where the staff queue could contain unpaid drafts.

### D7: Provider selected by configuration; Leegality retained

*Why:* the seam's entire purpose is a one-adapter swap, and retaining a second implementation is
what keeps the abstraction honest. It also gives a fallback if ZOOP onboarding stalls. Cost is
one extra adapter to keep compiling, which the interface change forces us to touch anyway.

### D8: Artifact host pinning becomes an allowlist

*Why:* ZOOP's signed-document URLs are expiring links on a different host from the API
(`esign.zoop.plus` in samples), so single-host pinning would break. An allowlist keeps the SSRF
guard while admitting a legitimate second host. Do not weaken it to "any https URL".

## Risks / Trade-offs

- **Signatures land in the wrong place.** The right-edge origin is easy to get backwards and
  fails silently. -> Visual verification on a rendered document as a manual-test gate (D4);
  refuse on missing anchor.
- **Webhook key leak allows forged callbacks for that transaction.** -> Body never trusted
  (D2), key encrypted at rest (D3), TLS in transit. Blast radius is one transaction, and the
  worst outcome is a spurious re-read of authoritative state, which is a no-op.
- **eKYC PII arrives in webhook and fetch payloads.** `fetched_name`, `given_name`,
  `postal_code`, `name_match_score` are richer than anything Leegality returned. -> Never
  logged, never exposed on the progress view, location/photo capture left off. The existing
  never-log-payloads rule now guards real PII rather than a theoretical case.
- **Aadhaar name mismatch fails mid-session.** `signer_name` is matched against Aadhaar and
  scored. -> `REJECTED` already models it; the correction-and-re-invite loop is real work and
  the acceptable `name_match_score` threshold is a policy decision, flagged as an open question.
- **Breaking the `EsignProvider` interface touches the Leegality adapter and its tests.** ->
  Contained: the header parameter is additive at the call site and Leegality ignores it.
- **`REQUIRED` mode is specified and tested but never runs.** Untested-in-production paths rot.
  -> Integration tests cover both modes, so the behaviour is exercised on every build even
  though production runs `OPTIONAL`.
- **14 MB encoded document ceiling** is now a live constraint, since the stamped PDF carries a
  scanned certificate page. -> Bound the scan at intake and check encoded size before `/init`.
- **Sequential signing means the tenant cannot start until the owner finishes.** Slower than
  parallel, and a stalled owner blocks everything. -> Correct for a document that must carry
  both signatures in order; extend/re-invite exists for the stall case. Revisit if owners
  routinely stall.

## Migration Plan

1. One forward-only Flyway migration: encrypted per-transaction webhook key on
   `signing_request`; payment state, amount, reference (unique where present), actor, and
   timestamp. All nullable so existing rows validate.
2. Ship the interface change, both adapters, and the migration together - the interface change
   does not compile in isolation.
3. Default configuration: provider `zoop`, base URL the **test** host, payment mode `OPTIONAL`.
   The production host is never a default.
4. Run the test-environment tracer (tasks S8) before enabling ZOOP anywhere else.

**Rollback:** flip the provider selector back to `leegality`; the schema additions are nullable
and additive, so no down migration is needed.

**Sequencing:** `manual-estamp-upload` must land first - signing now requires an attached stamp.

## Open Questions

- **What `name_match_score` do we accept?** A policy decision needing legal input, not a
  technical one. It changes a threshold constant, not the specs or the task breakdown.
- **Is ZOOP's audit-trail report the definitive legal artifact, or is the ESP eSign response XML
  retrievable separately?** We store whatever the audit endpoints return either way; if the XML
  turns out to be separately retrievable it is an additive fetch.
- **Exact validity of `complete_signed_url`.** We download and store immediately on completion,
  so any documented validity is sufficient; this only affects how tolerant reconciliation needs
  to be.
- **Rate limits on `/init` and the fetch endpoints.** Unknown; affects reconciliation polling
  cadence, not the design.
- **Which payment gateway, and does it change the gate's seam?** Deliberately deferred. The seam
  carries amount, currency, reference, and time - a superset of what any gateway confirmation
  provides.

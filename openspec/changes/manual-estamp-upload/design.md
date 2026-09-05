## Context

See `proposal.md` - Why. The constraints that shape the approach:

- `SigningRequestService.create()` today calls `ensureStamped(...)` inline, which calls
  `StampProvider.procure(agreementId, draftPdf)` and gets back a synthetic stamp. Everything
  happens inside one HTTP request.
- `PdfStampComposer` already does the right *shape* of work - prepend a page 1, overlay a
  per-page header, fail closed on bad PDFs, respect each page's media box and rotation. Only
  the **source of page 1** changes.
- `SecurityConfig` has no role concept. V11 added OAuth identity, V12 added agreement ownership.
- The `AM-<LAST6>-<DDMMYY>` tracking number is computed at render time in the `documents`
  module and never persisted; `document-footer-tracking-url` states its 24-bit fragment is not
  collision-free.
- Payment does not exist in the backend at all.

### Flow

```
Customer                 System                    Staff                SHCIL portal
   |                       |                         |                       |
   |-- finalise + pay ---->|                         |                       |
   |                       |-- reference ----------->|  (order queue)        |
   |<-- reference ---------|                         |                       |
   |                    [PDF_GENERATED]              |                       |
   |                       |                         |-- buy stamp --------->|
   |                       |                         |<-- certificate -------|
   |                       |                         |   (print + scan)      |
   |                       |<-- POST scan + metadata-|                       |
   |                       |   (STAFF role)          |                       |
   |                  [composite: scan = page 1]     |                       |
   |                    [STAMPED]                    |                       |
   |                       |                         |-- initiate eSign ---->  (zoop-esign-provider)
   |                    [SIGN_REQUESTED]             |                       |
```

The async signing/webhook flow after `SIGN_REQUESTED` is untouched by this change.

## Goals / Non-Goals

**Goals:**

- Make stamping an explicit, staff-driven, out-of-band step without disturbing the FSM's shape.
- Make an e-stamp certificate provably single-use, enforced by the database.
- Give staff a reference that resolves to exactly one agreement, every time.
- Keep the `StampProvider` seam so a future real procurement API is still a one-adapter swap.

**Non-Goals:**

- A staff console UI. This change delivers the API and the authorization; the operator surface
  can be Swagger or a minimal internal page until volume justifies more.
- Any SHCIL portal integration, credential, or automated certificate verification.
- Payment capture, order state, or the notification that tells staff an order is waiting.
- OCR of the scanned certificate to auto-fill metadata. Staff type it; the certificate number
  is the field that matters and it is validated for uniqueness, not correctness.

## Decisions

### D1: Keep `StampProvider`, change its contract

Replace `procure(agreementId, draftPdf)` with an attach-style operation taking the scan plus
its metadata, returning the same `StampResult` shape (extended with the real fields).

*Why:* the seam's value was never the synthetic generation - it was keeping stamp specifics out
of `SigningRequestService`. That value survives. Leegality auto-affix or a real SHCIL API
would both slot back in behind it later.

*Alternative rejected:* delete the seam and inline composition into the intake service. Fewer
moving parts today, but it discards the abstraction precisely when a second stamping model
(vendor auto-affix) is a live possibility - `docs/integrations/leegality.md` still lists
auto-affix as a real product feature.

### D2: Stamp intake owns the FSM transition, not the signing flow

The intake endpoint drives `PDF_GENERATED -> STAMPED` (or `STAMP_FAILED`). `createSignRequest`
only *reads* the stamp state and refuses with `409` if empty.

*Why:* it puts the transition next to the event that causes it, and it removes the awkward
situation where a customer-initiated signing call silently performs a staff action.

*Alternative rejected:* keep `ensureStamped` in `createSignRequest` and have it consume a
previously-uploaded scan. That leaves stamping's failure mode inside the signing call, so a
composition failure would surface to the wrong actor - the customer - who cannot fix it.

### D3: The staff reference is a separate, persisted, checksummed short code

Assign at creation, persist with a unique constraint. Format is a short human-safe code
(unambiguous alphabet, no `0/O`, `1/I/L`), with a check character so a mistyped code is
rejected rather than resolving to the wrong agreement.

*Why:* the reference travels through a **manual** loop - read off an order, carried to the
SHCIL portal, typed back hours later. A check character turns the dangerous failure (silently
stamping the wrong agreement) into a safe one (rejected input). This is the single most
important correctness property in the change, because the artifact being attached evidences
real money paid against a specific instrument.

*Alternative rejected:* reuse `AM-<LAST6>-<DDMMYY>`. Its own spec calls it not collision-free;
promoting a display veneer to a key is exactly the defect this change exists to avoid.

*Alternative rejected:* have staff paste the raw agreement UUID. Correct but 36 characters,
hostile to a manual workflow, and easy to truncate.

*Note:* the display tracking number stays as-is for customers. Two references with different
jobs is acceptable; conflating them is not.

### D4: Uniqueness of the certificate number is a database constraint

A unique index on the certificate column, with the `409` produced by catching the constraint
violation - not by a pre-check.

*Why:* a read-then-write check is a race. Two staff uploading the same certificate
concurrently must not both succeed, because that would spend one stamp on two agreements - a
legal defect that is invisible until challenged.

*Note:* normalise before storing (trim, uppercase) so `in-ka123...` and `IN-KA123...` collide
as they should.

### D5: Accept only JPEG/PNG, validate magic bytes, bound decoded pixels

Reject on declared-type/magic-byte mismatch; cap byte size and decoded dimensions.

*Why:* the byte-size cap alone does not bound memory - a small PNG can decode to a huge raster.
The existing draft-upload path only checks `%PDF-` magic bytes, and stamping already has a
fail-closed discipline; this extends the same posture to the second untrusted input.

*Note:* PDF was excluded because the product owner specified a scan. If staff later download
the SHCIL certificate PDF directly, accepting it would be strictly better (no rasterisation,
text stays selectable) - recorded as a follow-up, not built now.

### D6: Retain the scan as a separate blob

Store the scan under an agreement-scoped key and keep it after composition.

*Why:* the scan is the evidence artifact. If a composited PDF is ever disputed or needs
re-compositing after a template change, the source must still exist. Storage is cheap;
re-acquiring a purchased certificate is not.

### D7: Role on the account, not on a claim

Add a role column to the identity record, default CUSTOMER, never settable by the client and
never read from an OAuth claim.

*Why:* deriving privilege from an IdP-supplied claim means the IdP's user-editable profile
fields become an authorization surface. Granting STAFF stays a deliberate database action.

*Alternative rejected:* an allowlist of staff email addresses in configuration. Simpler, but
it splits authorization between config and database and does not survive an email change.

### D8: Model "ready to stamp" as observable state, not a payment hook

Staff find work by querying agreements resting in `PDF_GENERATED`. No payment integration is
assumed or stubbed.

*Why:* payment does not exist. Inventing an order entity now would almost certainly be wrong
by the time payment is real. `PDF_GENERATED` already means exactly "instrument generated,
stamp not yet attached", so the queue is a query, not a new concept.

*Consequence:* until payment exists, that queue contains unpaid drafts too. Acceptable while
staff work from the order side; the gap closes when payment lands, and it is called out in
`Open Questions` rather than papered over.

## Risks / Trade-offs

- **Wrong agreement gets stamped.** Staff type a reference that resolves to a different
  agreement, and a purchased certificate is spent on the wrong instrument. -> Check character
  in the reference (D3); intake response echoes enough non-PII context (reference, property
  city, agreement date) for staff to confirm before it becomes final; the `409` on
  already-stamped prevents silent overwrite.
- **One certificate spent twice.** -> Database unique constraint (D4), not an application
  check.
- **A scanned image is not machine-verifiable.** We record what staff typed; nothing proves the
  scan matches the certificate number, or that the certificate is genuine. -> Accepted for v1
  and stated in the spec (staff attest). SHCIL offers online certificate verification; wiring
  it in is the obvious follow-up hardening and is the single largest residual legal risk here.
- **Scan quality.** A skewed, cropped, or illegible scan produces a poor-quality legal
  instrument that composites successfully. -> Out of scope to detect automatically; mitigated
  by minimum-dimension validation and by staff review. Worth revisiting if it bites.
- **`PDF_GENERATED` is now durable.** Anything that treats it as transient - orphan recovery,
  future cleanup jobs - would now reap live work. -> Spec'd explicitly as durable; the queued
  "PDF_GENERATED orphan recovery" non-goal in `docs/ROADMAP.md` must be re-scoped before it is
  ever built.
- **Turnaround time becomes human.** Signing cannot start until a person completes a manual
  purchase. -> Inherent to the decision, not a design flaw. Worth surfacing SLA/aging on the
  staff queue later.
- **Removing the synthetic adapter removes the only stamping path that needed no human.** Local
  development and e2e tests must now upload a fixture scan. -> Provide a test fixture image and
  a documented local flow so the developer experience does not regress.

## Migration Plan

1. One forward-only Flyway migration: stamp columns on `agreement` (nullable), unique index on
   the normalised certificate number, the unique staff reference column, and the role column on
   the identity record defaulting to CUSTOMER.
2. Backfill the staff reference for existing agreements in the same migration, then apply the
   NOT NULL + unique constraint. Existing rows are dummy/dev data, so a simple derivation is
   sufficient.
3. Ship code and migration together; `ddl-auto: validate` must stay green.
4. Grant STAFF to the operator accounts out-of-band.

**Rollback:** the change is additive at the schema level (new nullable columns plus a new
identity column with a default), so rolling back the application is safe without a down
migration - forward-only is preserved. Reverting the application restores auto-stamping, which
would then produce synthetic stamps again; acceptable only in development.

**Sequencing:** this change must land before `zoop-esign-provider` is exercised end-to-end,
because the eSign flow now depends on a stamp being attached first.

## Open Questions

- **Which state does the stamp jurisdiction come from?** The agreement already knows its state
  (template layers are state-scoped). Whether intake should *validate* that the certificate's
  jurisdiction matches the agreement's state, or merely record it, is a legal question. Recorded
  for now; validation is a small additive rule that changes no interface.
- **Does the duty amount need to be checked against a rate table?** Requires the multi-state
  rules engine (`rules` module, not built). Recording the amount is enough today.
- **Staff queue ergonomics** - filtering, aging, assignment. Deferred until there is operational
  experience; none of it changes the intake contract.
- **When payment lands, does the queue become order-driven?** D8's consequence resolves then.
  It does not change this change's specs.

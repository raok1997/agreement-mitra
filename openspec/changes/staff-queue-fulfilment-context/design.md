## Context

See `proposal.md` - Why. The constraints that actually shape the approach:

- **`Signer` is package-private** to `in.agreementmitra.signing.agreement`. Only code in that
  package can read `firstName` / `lastName` / `fatherName` / `role`, so the party projection has
  to be built there and handed out as a value object. `StampIntakeService` (in
  `signing.signingrequest`) assembles the queue but cannot reach a signer.
- **The template lives in another module.** `agreement.template_id` is a bare `UUID` held by
  `signing` on purpose; the name and state belong to `documents`. The only sanctioned route is
  the `documents.api.TemplateCatalogApi` port - which `AgreementService` **already injects**
  (`AgreementService:44`), so this change adds no new cross-module edge.
- **`TemplateCatalogApi.detail(id)` throws.** It serves `PUBLISHED` entries only and raises
  `ResourceNotFoundException` for anything else - unknown id, malformed id, archived, or
  superseded. It is deliberately 404-shaped so the HTTP surface is not an existence oracle.
- **The queue is a batch read** capped at `QUEUE_LIMIT = 200` rows, assembled per request from
  three already-batched agreement-side lookups (staff views, payment states, closure states).
- **The row is a work item, not a record.** Nothing downstream consumes `StampQueueEntry`; it is
  read by an operator and by nothing else. That is what makes widening it a contained decision.

## Goals / Non-Goals

**Goals:**

- An operator can complete the SHCIL purchase form from the queue row alone, without a second
  lookup.
- The widening is legible in the code: the projection type says what staff may see and why, so
  the next person to add a field has to argue with a stated rationale rather than a blank record.
- Template lookup costs one call per **distinct** template across the whole page, not one per row.
- Party names cannot reach a log by accident.

**Non-Goals:**

- No general "staff can view an agreement" capability. This widens one work-queue projection; it
  does not open the agreement to staff, and the exclusion list is part of the contract.
- No search, no pagination, no auto-refresh - separate gaps, separate changes.
- No new port method on the `documents` module API.
- No caching of template metadata across requests.

## Decisions

### D1: Build the party projection in `signing.agreement`, expose it as a public value record

`AgreementService.toStaffView` gains the parties; a new **public** record
`StaffPartyView(Role role, String name, String fatherName)` sits beside `StaffAgreementView` in
the `agreement` package (public for the same reason `Role` already is - the module's `api`
records name it). `StaffAgreementView` gains `List<StaffPartyView> parties`, `String
templateName`, `String templateState`.

**Why:** it is the only package that can read a `Signer`, and it is where the existing staff
projection already lives. **Alternative rejected:** a repository query returning names straight
to `StampIntakeService` - that would let a second package assemble a PII projection with no
single place stating the rules, which is exactly the failure mode this change is trying not to
repeat.

**Name used:** `Signer.name()` - the full name as per Aadhaar, the same value handed to the eSign
provider and therefore the one that must match the certificate. Not a re-join of
`firstName + lastName`, which would drift from an overridden name.

**Ordering:** owners before tenants, each group in the aggregate's stored order, so "first party"
and "second party" are stable across refreshes rather than dependent on row order.

### D2: Resolve templates once per distinct id, and treat a missing one as absent

`staffViewsByAgreementId` collects the distinct non-null `templateId`s across the batch and
resolves each once through the catalog port. Rows with an unresolved (or null) template get
`null` name and state.

**REVISED DURING IMPLEMENTATION.** This decision originally said: call the existing throwing
`TemplateCatalogApi.detail` and `catch (ResourceNotFoundException)`, on the reasoning that a local
catch beats permanent extra API surface. That is wrong, and the integration test caught it - a
deprecated pinned template returned **HTTP 500 for the entire queue**, not a degraded row.

**Why the catch cannot work:** `detail` is `@Transactional(readOnly = true)` and propagation is
`REQUIRED`, so it *joins the caller's transaction*. A RuntimeException crossing that boundary marks
the shared transaction **rollback-only**. The caller's `catch` then swallows the exception and
returns normally - and the commit fails with `UnexpectedRollbackException`, surfacing as a 500 that
names nothing about templates. Catching is not enough across a transactional boundary; the
exception has to not happen.

**Revised decision:** `TemplateCatalogApi` gains `Optional<TemplateDetail> find(String id)` - the
non-throwing sibling of `detail`, published-only and equally non-oracular (empty for unknown *and*
non-published, so it discloses no more than `detail` did). The original note said this method would
"earn its place" if a second caller needed it; a correctness constraint turned out to be the thing
that earned it instead.

**Cost accepted:** one extra method on a public module API. Cheaper than the alternative, which was
a queue that 500s the moment the catalog deprecates any pinned template - the exact failure this
decision exists to prevent.

**Why dedupe:** 200 rows in one state/type will overwhelmingly share a handful of templates.
Per-row lookup would be an N+1 against the catalog for no benefit.

**Why not fail the whole queue on a missing template:** the queue's job is to show outstanding
work. An archived template is a catalog lifecycle event; making it hide an unstamped paid order
would turn a cosmetic gap into lost work. Degrading the two fields is the strictly smaller
failure.

### D3: The state is the template's state dimension

`TemplateDetail.Dimensions.state()`, not the property address. The address is free text whose
only machine-extractable part is the comma-separated tail (the existing `propertyCity` derivation
is exactly that heuristic). Stamp duty follows the state whose law the instrument was drafted
under, which is precisely what the template pin records. The city stays on the row as human
context, clearly subordinate to the state.

### D4: Redaction discipline is a test, not a convention

`StaffAgreementView` and `StaffPartyView` override `toString()` to emit non-identifying content
only (`Signer.toString()` already sets this precedent: id + role, never the name). A unit test
asserts that neither type's `toString()` contains a name it was constructed with, so a future
`log.debug("...{}", view)` cannot leak a party name.

**Why a test:** "never log PII" is a convention that a record's auto-generated `toString()`
silently violates the moment anyone interpolates one. A record's default `toString()` prints
every component - the risk here is not carelessness, it is the language default.

### D5: The javadoc rationale is replaced, not appended

The current text on `StaffAgreementView` ("staff need to disambiguate, not to read the customer's
agreement") becomes false with this change and must not survive next to the new fields, or the
type will document a rule its own shape breaks. It is replaced with the fulfilment rationale plus
the explicit exclusion list, so the record still states a boundary - a different one.

### D6: Frontend leads with the state

The row is reorganised around what the operator acts on: template name and **state** on the
headline line beside the reference (the state as a distinct badge, since it selects which stamp
to buy), and the parties beneath as `Name` with `Father: <name>` under it, grouped under "First
party" / "Second party" labels. Absent template metadata renders as a plain "Template
unavailable" note rather than an empty gap, so a degraded row reads as degraded rather than
broken.

**Why "Father:" and not "S/o":** Indian instruments use S/o, D/o or W/o depending on the party,
and we store only a father's name - no gender, no relationship. Rendering "S/o" for every party
would misdescribe some of them on a legal instrument. The neutral label carries the same
information without asserting a relationship we did not capture. If the vendor form needs the
S/o-D/o form, capturing the relationship is a separate change to the capture model, not a guess
made in the console.

## Risks / Trade-offs

- **A STAFF account can now read party names for every outstanding order** -> Accepted and
  intended; it is what purchasing requires. Mitigated by keeping the role grantable only by an
  out-of-band database action, by server-side enforcement in the filter chain ahead of any
  handler, and by holding the exclusion list (contacts, rent, deposit, full address) in the spec
  so the next widening is a decision rather than a drift.
- **Names in a JSON response are names in a browser cache, a proxy log, and a screenshot** ->
  Mitigated for our own logs by D4; the rest is inherent to showing an operator the data they
  need. Worth revisiting under a real-PII deployment alongside the wider audit-logging question,
  not resolvable here.
- **The `documents` public API grew a method for one caller** -> Accepted under D2: the throwing
  lookup cannot be made safe inside a caller's transaction, so this was a correctness requirement
  rather than a convenience. `find` is a strict narrowing of `detail` (same published-only rule,
  same non-oracle behaviour), so it widens the API's surface without widening what it discloses.
- **The same rollback-only trap will catch the next caller** who reaches for `detail` inside a
  transaction and "handles" the 404 -> Mitigated by documenting it on both methods' javadoc, at the
  call site, and in the unit test's comment. It is invisible at the call site otherwise, which is
  what made it worth three separate notes.
- **Up to N distinct catalog lookups per queue load** -> Bounded by distinct templates (a
  handful), read-only, and against a small table. If the queue ever paginates past 200 this is
  the first thing to batch.
- **The queue's shape is now load-bearing for a manual vendor workflow** -> If SHCIL's form ever
  needs a field we excluded, that is a spec change, not a quiet addition. Stated here so the
  exclusion list is understood as deliberate rather than as an oversight to be corrected.

## Migration Plan

None required. No schema change, no migration, no data backfill - every field already exists
(`signer.first_name`, `signer.last_name`, `signer.father_name`, `signer.role`,
`agreement.template_id`). The API response gains fields, which is backwards-compatible for the
only client that reads it. Rollback is a revert; nothing is written that would outlive it.

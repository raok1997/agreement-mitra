## Context

Post-CR-5, an agreement carries an uploaded draft PDF (`draftPdfKey`) and
`createSignRequest` submits that **bare draft** to Leegality. A rental agreement must be
executed on stamp paper — stamp duty is paid on the instrument *before* signing. CR-6
inserts a stamping step so the artifact that gets eSigned is a stamped instrument, and
records the legal ordering (`STAMPED` precedes `SIGN_REQUESTED`) in the signing FSM.

This is **sandbox/dummy-data infra**: the v1 stamp is synthetic (no real duty), so the
work is local PDF composition + a provider seam, with no new external dependency on a
duty-payment service. The change touches the signing FSM and `createSignRequest`, so it
is a signing-flow change handled with care.

Relevant prior decisions carried in:
- **CR-2**: `Agreement` is status-less; the FSM lives on `SigningRequest`.
- **CR-3 (D9)**: persist before the provider call; no DB transaction spans the vendor
  HTTP round-trip; the pre-request FSM state is `PDF_GENERATED`.
- **CR-4**: blob keys derive from internal ids only (never provider/external ids); a
  `BlobStore` seam over MinIO exists.
- **CR-5**: lock-forever — exactly one signing request per agreement; draft sourced from
  `blobStore.get(draftKey)`; `signingrequest → agreement` reads via an `AgreementService`
  accessor; agreement↔signingrequest cycle avoided via the `SigningRequestQuery` seam.

## Goals / Non-Goals

**Goals:**
- Composite a synthetic ₹100 Karnataka e-stamp onto the draft and submit the **stamped**
  PDF to the provider, transparently inside `createSignRequest` (no new endpoint).
- Introduce `STAMPED` (+ `STAMP_FAILED`) into the SigningRequest FSM.
- Store stamp **data** on the Agreement (descriptive value object), stamp **lifecycle**
  on the SigningRequest — without reversing CR-2's status-less Agreement.
- A `StampProvider` seam parallel to `EsignProvider`, so a real procurement adapter swaps
  in later with no caller change.
- Preserve module boundaries (no new cycle, `ModularityTests` green) and D9 tx discipline.

**Non-Goals:**
- Real/paid e-stamp procurement (SHCIL / state portal) — future adapter behind the seam.
- Multi-state stamp templates — v1 is Karnataka ₹100 non-judicial only.
- `.docx` / our-template ingestion (still deferred).
- The paid draft-revision / supersede flow — it owns the rebuy-vs-reuse legal decision
  and would relocate procurement semantics; the reuse branch here is dormant in v1.
- `signing-auth` (authZ / rate-limit / security-event logging), PDF_GENERATED-orphan
  recovery, ShedLock distributed scheduler lock, prod object-store hardening — all
  out of scope, unchanged from prior CRs.

## Decisions

### D1 — Auto-stamp inside `createSignRequest`; no new endpoint
Stamping is transparent to the caller and the FSM was built for it (CR-3 kept
`PDF_GENERATED` distinct from `SIGN_REQUESTED` precisely so a state could be inserted).
Flow: source stored draft → ensure stamp (`STAMPED`) → submit stamped PDF to
`EsignProvider` → `SIGN_REQUESTED`.
*Alternative considered:* a separate `POST /api/agreements/{id}/stamp`. Rejected — adds
another security permit + 409 gate + splits a flow the user never sees in two. Testable
without it (integration test drives `createSignRequest`).

### D2 — Data on Agreement, lifecycle on SigningRequest
Stamp **data** (`StampInfo`) lives on the `Agreement` aggregate; the **FSM state**
(`STAMPED`/`STAMP_FAILED`) lives on the `SigningRequest`. This does **not** reverse CR-2:
CR-2 forbade a *status/FSM* on the Agreement, and `StampInfo` is descriptive data (like
`draftPdfKey`), not a status. Putting the stamp data on the Agreement (not the
SigningRequest) makes it correct under a future multi-signing-request world where one
stamp is reused across attempts — without building that reuse now.
*Alternative considered:* stamp data on SigningRequest. Cleaner only under v1's 1:1
invariant; would force a relocation/migration when the supersede flow lands. Rejected.

### D3 — `StampInfo` embedded value object, internal, all-nullable
`StampInfo { serial, stampedPdfKey, denomination, jurisdiction, dutyPaid, procuredAt }`
as a JPA `@Embeddable` (Java record-style value object) on `Agreement`, all columns
nullable (empty until procured). Exposed only via an internal `AgreementService` accessor
— **not** on the public `AgreementResponse` — mirroring how `draftPdfKey` is handled.
`V6__stamp.sql` adds the nullable columns to the `agreement` table; JPA stays
`ddl-auto: validate`. `jurisdiction` is persisted as `"KA"` (constant in v1) so the field
is real, not hardcoded-and-deferred. **`dutyPaid` invariant:** the provider's
`StampResult.dutyPaid` is authoritative — `StampInfo.dutyPaid` is copied from it verbatim
at procure time and never re-derived, so the persisted value can never disagree with what
the provider actually did (matters once a real paid adapter lands).

### D4 — `StampProvider` seam + synthetic Karnataka adapter
New `StampProvider` interface (in a `signing.stamp` sub-package) parallel to
`EsignProvider`, returning `StampResult { serial, jurisdiction, denomination, dutyPaid,
stampedPdf (bytes) }`. (Implementation note: `StampResult` carries `jurisdiction` +
`denomination` in addition to the originally-sketched `{serial, stampedPdf, dutyPaid}`,
so the *provider* is the source of truth for what it stamped — `jurisdiction="KA"` /
₹100 — and the service stays provider-agnostic when building `StampInfo`.) v1
impl is a synthetic Karnataka ₹100 non-judicial adapter: `dutyPaid = false`, synthetic
BW-series serial, hardcoded stamp-paper template, no external endpoint/credential. The
adapter package stays module-internal (`ModularityTests` green); a real SHCIL/portal
adapter replaces it later with no caller change.

### D5 — Deterministic synthetic serial
Serial derived deterministically from the agreement id (e.g. a stable hash → fixed-width
digits, formatted `BW <digits>`). Reproducible + collision-free across runs; never
`Math.random`/wall-clock. Obviously synthetic so it can't be mistaken for a real stamp.
(Also avoids the workflow/runtime ban on non-deterministic entropy and keeps tests
deterministic.)

### D6 — PDFBox for composition, not Chromium
Apache PDFBox prepends a hardcoded ₹100 stamp-paper page as page 1, then overlays a
per-page header `Non Judicial Stamp No. BW XXXXX` on the draft pages. Result page count =
`1 + draftPages`. **PDFBox, not Chromium:** the CLAUDE.md "use Chromium not a pure-Java
PDF lib" rule is about *shaping complex Indic scripts during rendering* — page-prepend and
ASCII overlay on an already-rendered PDF is not shaping, so PDFBox is correct and far
lighter. (Called out explicitly to preempt a reviewer flag.) PDFBox is a new **shipping**
dependency → enters OSV + SpotBugs scan scope; the Gradle lockfile is regenerated.

**Geometry + font:** draft pages may be any size/orientation (A4, Letter, landscape,
rotated). The overlay SHALL be positioned relative to **each page's own media box** (and
respect its rotation), not absolute coordinates, so the header lands in-frame regardless
of page dimensions. The prepended ₹100 stamp template is its own fixed (A4 portrait) page
and does not need to match the draft's size. The overlay uses a **Standard-14 font**
(Helvetica) — the serial text is ASCII (`BW <digits>`), so no font file needs bundling and
rendering is environment-independent.

### D7 — Agreement-scoped stamped-PDF key (a reasoned divergence from signed-artifact scoping)
Stamped PDF stored via the existing `BlobStore` at `stamped/{agreement-id}.pdf`. Two
distinct key rules are in play and must not be conflated:
- **Internal-id derivation (CR-4 hygiene) — satisfied:** the key derives from the internal
  agreement id, never a provider/external id. ✔
- **Request-scoping (signed-artifact-storage) — deliberately diverged:** the
  `signed-artifact-storage` capability keys signed/audit blobs from the internal
  *signing-request* UUID specifically to avoid one re-request overwriting a prior
  request's artifact. The stamp key is **agreement-scoped instead**, because the stamp
  belongs to the *instrument* (the agreement), not to a particular signing attempt, and is
  meant to be reused across attempts. This is safe under CR-5 **lock-forever (1:1
  agreement↔request)** — there is no second request to overwrite — and is the *correct*
  long-term home for a stamp that should be procured once and reused. When the supersede
  flow introduces multiple requests per agreement, the stamp stays agreement-scoped (one
  stamp per instrument, reused) — the opposite of the signed-artifact rule, by design.

Bytes never touch Postgres; the overwrite at this fixed key is idempotent (re-stamping the
same agreement writes identical content to the same key).

### D8 — Procure-if-empty, else reuse (idempotent); no tx spans a network call
Inside `createSignRequest`, after the pre-request persist (`PDF_GENERATED`), with each
step's transaction boundary made explicit so **no DB transaction is ever open across a
network call** (neither `blobStore.put`/MinIO nor the provider HTTP call — the CR-3
no-tx-spans-the-vendor-call discipline):

1. `if agreement.stampInfo empty` →
   `StampProvider.procure()` (PDFBox compose — **no tx**) →
   `blobStore.put("stamped/{agreement-id}.pdf", bytes)` (network — **no tx**) →
   **then a short tx**: `AgreementService.attachStamp(stampInfo)` + `request.markStamped()`
   (DB only, opened *after* the put returns, committed before anything else).
2. `else` (stampInfo populated) → short tx: reuse existing `stampedPdfKey`/serial +
   `request.markStamped()` (no network).
3. Load the stamped PDF bytes (`blobStore.get`, no tx) and call `EsignProvider` (no tx
   held), then a short tx for `markSignRequested()`.

On stamp procurement/composition failure (including an unparseable/encrypted/zero-page
draft — see D10) → short tx `request.markStampFailed()` (terminal), provider not called.

The **reuse branch (step 2) is dormant in v1** (lock-forever ⇒ stampInfo always empty on
the only request). It is kept as a one-line safe-reuse default that aligns with the
agreement-scoped model, but because the real flow cannot reach it under v1, its spec
scenario is marked **forward-looking / unit-level only** (exercised by constructing the
populated-stampInfo state directly in a unit test, not via an integration path). The
rebuy-vs-reuse legal call is deferred to the supersede feature. *Forward note:* under a
future multi-request world the read-stampInfo / procure+put / attachStamp sequence needs a
guard against a concurrent procure (lean on the `@Version` column, as the signed-artifact
path does); out of scope for v1's 1:1.

### D9 — Module boundaries / no new cycle
`agreement` and `signingrequest` are sub-packages of the one `signing` Modulith module, so
intra-module calls are allowed; the existing seam (`SigningRequestQuery`) exists to avoid a
*cycle*. The new `AgreementService.attachStamp(...)` / `stampInfo(...)` accessors are the
**same direction** as the CR-5 draft read (`signingrequest → agreement`) — no new cycle.
`SigningRequestQuery` is unchanged. `ModularityTests` stays green.

### D10 — Untrusted-draft parsing fails closed to STAMP_FAILED
The draft PDF is **user-uploaded and untrusted** (CR-5 validated only the `%PDF-` magic
bytes, not full structure), and CR-6 is the first step to fully parse it (PDFBox). Every
parse/compose failure mode SHALL be caught and mapped to `STAMP_FAILED` (a clean terminal
state + an RFC 9457 error response), never an unmapped 500, a hung request thread, or a
JVM OOM: encrypted/password-protected PDF, corrupt/truncated PDF, zero-page PDF, and
structurally pathological (deeply nested / huge object-count) PDFs. Byte-size DoS is
already bounded by CR-5's **10 MiB upload ceiling**; PDFBox parses with a bounded
memory/scratch-file setting so a large-but-legal file can't exhaust heap. This is a
synchronous step on the request thread, but it operates on an already-size-bounded blob,
so it does not violate the async-signing contract (no signature wait).

Trade-off (documented): under v1 lock-forever (1:1), `STAMP_FAILED` is terminal with no
retry path, so even a **transient** parse failure permanently ends that agreement's only
signing request — recovery means a new agreement (or the future supersede flow). Accepted
for v1 sandbox; same family as the deferred PDF_GENERATED-orphan-recovery non-goal.

## Risks / Trade-offs

- **[New shipping dep — PDFBox — widens the vuln surface]** → it enters the OSV +
  SpotBugs scan scope (per the CR-9 shipping-config policy); regenerate the lockfile and
  let `securityScan` gate it. Pin to a current PDFBox release; no suppression unless a
  clean transitive CVE forces a time-boxed one.
- **[Stamp data on Agreement under v1's 1:1 model could be read as premature generality]**
  → it is the *correct* home (the stamp belongs to the instrument); the only thing
  deferred is the cross-request reuse decision, and the reuse code path is a one-line
  dormant default, not speculative machinery.
- **[Stamp succeeds, then provider call fails → STAMPED row with no SIGN_REQUESTED]** →
  acceptable and consistent with CR-3: the stamped PDF + stampInfo are persisted and
  reusable; PDF_GENERATED/STAMPED-orphan recovery is an already-deferred non-goal. The
  blob write is idempotent (overwrite at the same agreement-scoped key).
- **[STAMP_FAILED as a new terminal state could confuse reconciliation]** → reconciliation
  scans `SIGN_REQUESTED`-stale and `SIGNED`-null-keys only; `STAMP_FAILED` (like
  `PDF_GENERATED`) is excluded, so no behavior change there. Guarded by an explicit
  negative test that a `STAMP_FAILED` row is never picked up by reconciliation.
- **[PDFBox parses an untrusted, user-uploaded PDF — malformed/malicious-PDF DoS]** →
  see D10: every failure maps to `STAMP_FAILED` (fail closed), input bytes are bounded by
  CR-5's 10 MiB upload cap, and PDFBox runs with a bounded memory setting. The new shipping
  dep is gated by `securityScan`; bias toward bumping/pinning to clear any parser CVE
  rather than suppressing (a suppressed parser CVE on an untrusted-input path is
  higher-risk than the generic policy implies).
- **[Hardcoded single-state Karnataka template]** → explicit v1 non-goal; multi-state is a
  future CR behind the same seam. The template is a static asset, not logic.

## Migration Plan

- `V6__stamp.sql`: forward-only; add nullable stamp columns to `agreement`. No edit to
  `V1`–`V5`. `flyway.clean` stays disabled; `ddl-auto: validate` everywhere.
- Rollback: the columns are nullable and unused by existing rows, so a rollback to the
  prior build leaves the schema forward-compatible (the columns are simply ignored). No
  data backfill.
- New blob key prefix `stamped/` in the existing bucket — no infra change.
- CLAUDE.md signing-states list updated to include `STAMPED` (+ `STAMP_FAILED`).

## Open Questions

None — the model decisions (trigger point, data-vs-lifecycle split, serial source) were
resolved with the user before this proposal.

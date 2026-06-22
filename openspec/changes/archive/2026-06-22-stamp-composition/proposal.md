## Why

A signed rental agreement is only legally meaningful if executed on stamp paper —
stamp duty is paid on the instrument *before* it is signed. Today (post CR-5) the
document fed to the signing provider is the purchaser's bare uploaded draft, with no
stamp. CR-6 composites a (synthetic, sandbox) ₹100 Karnataka non-judicial e-stamp onto
that draft so the artifact that gets eSigned is the legally-stampable instrument, and
records the stamp ordering (`STAMPED` precedes `SIGN_REQUESTED`) in the signing FSM.

## What Changes

- **New `StampProvider` seam** (parallel to `EsignProvider`): `procure()` returns a
  stamp `{ serial, stampedPdf, dutyPaid }`. v1 adapter is a **synthetic** Karnataka
  ₹100 non-judicial BW-series stub (`dutyPaid = false`) — a real SHCIL/state-portal
  adapter swaps in behind it later with no caller change.
- **PDFBox composition**: prepend a hardcoded ₹100 stamp-paper template as page 1, then
  overlay a per-page header `Non Judicial Stamp No. BW XXXXX` on the draft pages. PDFBox
  (not Chromium) — page-prepend/overlay is not Indic-script *shaping*, so the
  "use Chromium not a pure-Java PDF lib" gotcha does not apply here.
- **Deterministic synthetic serial** derived from an internal id (reproducible,
  collision-free, obviously a dummy — never `Math.random`).
- **Agreement gains stamp DATA**, not status: an `@Embedded`, all-nullable `StampInfo`
  value object (`serial`, `stampedPdfKey`, `denomination`, `jurisdiction`, `dutyPaid`,
  `procuredAt`), persisted via a new `V6__stamp.sql` migration adding nullable columns to
  the `agreement` table. Kept internal (not on the public `AgreementResponse`), mirroring
  how `draft_pdf_key` is handled. This does **not** reverse CR-2's status-less-Agreement
  decision — the lifecycle stays on the SigningRequest FSM.
- **Signing FSM gains `STAMPED` (+ `STAMP_FAILED`)**: `PDF_GENERATED → STAMPED →
  SIGN_REQUESTED`, with a `STAMP_FAILED` branch off the stamp step. `STAMPED` = "a stamp
  is confirmed attached to this request's instrument" (freshly procured *or* reused).
- **`createSignRequest` auto-stamps** — no new endpoint, transparent to the caller. It
  sources the stored draft → stamps → `STAMPED` → hands the **stamped** PDF (not the raw
  draft) to `EsignProvider` → `SIGN_REQUESTED`. If `agreement.stampInfo` is already
  populated it **reuses** (idempotent — no re-procure/re-stamp).
- **Stamp blob** stored via the existing `BlobStore` at `stamped/{agreement-id}.pdf`
  (internal-id-derived — satisfies CR-4 hygiene). Note this is **agreement-scoped**, a
  deliberate divergence from `signed-artifact-storage`'s *request*-scoping: the stamp
  belongs to the instrument and is reused across signing attempts; safe under CR-5
  lock-forever (see design D7).
- **CLAUDE.md** signing-states list updated to include `STAMPED` (+ `STAMP_FAILED`).

## Capabilities

### New Capabilities
- `document-stamping`: procuring (synthetic) stamp duty and compositing an e-stamp onto a
  rental-agreement PDF — the `StampProvider` seam, the synthetic Karnataka ₹100 adapter,
  deterministic serial generation, and PDFBox page-prepend + per-page overlay.

### Modified Capabilities
- `signing-request`: the FSM gains `STAMPED` + `STAMP_FAILED`; `createSignRequest`
  auto-stamps before the provider call and submits the **stamped** PDF instead of the raw
  draft; reuse/idempotency guard on an already-stamped agreement.
- `agreement-management`: the Agreement aggregate gains an internal, all-nullable
  `StampInfo` value object (descriptive data only — no status), populated when a stamp is
  procured; not exposed on `AgreementResponse`.

## Impact

- **Code** (`in.agreementmitra.signing`): new `StampProvider` interface + synthetic stub
  adapter + PDFBox composition helper + serial generator (new `signing.stamp` sub-package);
  `Agreement` aggregate + `AgreementService` (new `attachStamp(...)` / `stampInfo(...)`
  accessors, same `signingrequest → agreement` direction as the CR-5 draft read — no new
  module cycle); `SigningRequest` FSM + `SigningRequestService.createSignRequest`.
- **DB**: `V6__stamp.sql` (nullable stamp columns on `agreement`); JPA stays
  `ddl-auto: validate`.
- **Object storage**: new `stamped/` key prefix in the existing bucket.
- **Dependencies**: Apache PDFBox added to the backend (shipping classpath → enters the
  OSV/SpotBugs scan scope; lockfile regenerated).
- **Transactions**: stamp step (populate `agreement.stampInfo` + persist the `STAMPED`
  transition) runs as short txs **before** the `EsignProvider` round-trip — D9 discipline
  preserved, no tx spanning the vendor call.
- **Modularity**: `agreement` & `signingrequest` remain sub-packages of the one `signing`
  module; `SigningRequestQuery` seam unchanged; `ModularityTests` stays green.
- **Out of scope / unchanged**: no new API endpoint; no auth changes; Leegality creds not
  required (testable against the synthetic stub + WireMock + Testcontainers).

## PII / Security review checklist

- **Aadhaar/OTP/VID/PII flow introduced or moved?** None. Stamping operates on the
  agreement PDF and a synthetic stamp serial; it does not touch Aadhaar, OTP, VID, or
  signer KYC. The stamp serial is a non-judicial-stamp identifier, not personal data.
- **Secrets introduced or moved?** None. The v1 `StampProvider` is a synthetic local stub
  — no external endpoint, no API key, no credential. (A real procurement adapter would
  introduce secrets later, via env vars only — out of scope here.)
- **Redaction / logging.** No new PII in logs. `StampInfo` carries only a synthetic serial
  + key + denomination; `toString`/logs stay id-only, consistent with existing aggregate
  PII hygiene. The stamped PDF is a blob (never logged).
- **Sandbox + dummy data only — preserved.** The stamp is synthetic and `dutyPaid = false`;
  no real duty is paid and no real stamp paper is procured.
- **New attack surface — parsing the untrusted uploaded draft.** CR-6 is the first step
  to fully parse the user-uploaded draft PDF (PDFBox); CR-5 validated only the `%PDF-`
  magic bytes. This introduces a malformed/malicious-PDF risk (encrypted, corrupt,
  zero-page, decompression-bomb). **Mitigation:** input bytes are bounded by CR-5's 10 MiB
  upload ceiling; PDFBox parses with a bounded memory setting; every parse/compose failure
  fails **closed** to `STAMP_FAILED` (never an unmapped 500/hang/OOM). The new shipping
  PDFBox dep enters the OSV/SpotBugs scan gate, with bump/pin preferred over suppression
  for any parser CVE (see design D10 + Risks).

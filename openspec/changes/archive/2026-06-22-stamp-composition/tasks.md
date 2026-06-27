## 1. Dependency & schema

- [x] 1.1 Add Apache PDFBox as a backend **implementation** (shipping) dependency in `build.gradle.kts`; regenerate the Gradle lockfile (`./gradlew dependencies --write-locks`) so it enters the OSV/SpotBugs scan scope.
- [x] 1.2 Confirm `./gradlew securityScan` is clean with PDFBox added. **Bias to clear** any OSV/FindSecBugs finding by bumping/pinning PDFBox to a fixed release (a suppressed parser CVE on an untrusted-input path is high-risk); only if no fix exists, add a time-boxed `[[IgnoredVulns]]` with reason + expiry (never permanent/wildcard).
- [x] 1.3 Write `V6__stamp.sql` (forward-only; do not edit V1–V5): add nullable stamp columns to `agreement` with explicit, bounded types — `stamp_serial VARCHAR(40)`, `stamped_pdf_key TEXT`, `stamp_denomination INTEGER` (rupees), `stamp_jurisdiction VARCHAR(8)`, `stamp_duty_paid BOOLEAN`, `stamp_procured_at TIMESTAMPTZ` (all NULL-able so existing rows validate).

## 2. Stamp data on the Agreement aggregate

- [x] 2.1 Add an `@Embeddable` `StampInfo` value object (`serial`, `stampedPdfKey`, `denomination`, `jurisdiction`, `dutyPaid`, `procuredAt`), all-nullable, embedded on the `Agreement` entity; map columns to match `V6`.
- [x] 2.2 Keep `StampInfo` internal — NOT on the public `AgreementResponse`; add an internal `AgreementService.stampInfo(agreementId)` accessor and an `AgreementService.attachStamp(agreementId, StampInfo)` mutator (mirror the `draftPdfKey` accessor pattern; not client-settable). Confirm no controller exposes these accessors (no read/write path to stamp data over HTTP).
- [x] 2.3 Ensure `Agreement` stays status-less and PII-hygienic: no signing-status field added; `toString`/logging stays id-only (no PDF bytes, no PII).

## 3. StampProvider seam + synthetic Karnataka adapter

- [x] 3.1 Define the `StampProvider` interface and `StampResult` record (`serial`, `stampedPdf` bytes, `dutyPaid`) in a module-internal `signing.stamp` package (parallel to `EsignProvider`).
- [x] 3.2 Implement a deterministic synthetic BW-series serial generator derived from the agreement id (reproducible, collision-free; never `Math.random`/wall-clock).
- [x] 3.3 Implement the PDFBox composition helper: prepend a hardcoded ₹100 non-judicial stamp-paper page as page 1, overlay `Non Judicial Stamp No. BW XXXXX` per draft page; result page count = `1 + draftPages`; do not mutate the input draft bytes. Position the overlay relative to each page's media box (respect rotation; handle landscape/non-A4); use a Standard-14 font (Helvetica, ASCII serial — no bundled font). Parse defensively with a bounded memory setting and translate every parse/compose failure (encrypted, corrupt, zero-page, pathological) into a typed stamping-failure (no unmapped exception, no hang, no OOM).
- [x] 3.4 Implement the v1 synthetic Karnataka `StampProvider` (`dutyPaid = false`, synthetic serial, hardcoded template) wiring the serial generator + composition helper; add the static stamp-paper template asset.

## 4. Auto-stamp wiring in createSignRequest + FSM

- [x] 4.1 Add `STAMPED` and `STAMP_FAILED` to the SigningRequest status enum/FSM; add `markStamped()` / `markStampFailed()` transitions (legal path `PDF_GENERATED → STAMPED → SIGN_REQUESTED`; `STAMP_FAILED` terminal off the stamp step); reject illegal/out-of-terminal transitions.
- [x] 4.2 In `createSignRequest`, after the pre-request persist and before the provider call: if `agreement.stampInfo` empty → `StampProvider.procure()` (no tx) → `blobStore.put("stamped/{agreement-id}.pdf", bytes)` (network, no tx) → **then** open a short DB tx for `AgreementService.attachStamp(...)` + `markStamped()`; else → short tx reuse existing key/serial + `markStamped()`. CRITICAL (D9): the DB tx opens strictly **after** the `blobStore.put` returns — no transaction spans the put or the provider call.
- [x] 4.3 Source the **stamped** PDF bytes (`blobStore.get(stampedPdfKey)`) and submit that — not the raw draft — to `EsignProvider`; then `markSignRequested()`.
- [x] 4.4 On any stamp procurement/composition failure — including an unparseable draft (encrypted, corrupt, zero-page, pathological) — → `markStampFailed()` (terminal), do not call the provider; surface a mapped RFC 9457 `application/problem+json` error (no unmapped 500). Confirm reconciliation never selects `STAMP_FAILED` rows (its scan is `SIGN_REQUESTED`-stale / `SIGNED`-null-keys only).
- [x] 4.5 Confirm no new module cycle (accessors are `signingrequest → agreement`, same direction as the CR-5 draft read; `SigningRequestQuery` unchanged).

## 5. Docs

- [x] 5.1 Update the CLAUDE.md signing-states list to include `STAMPED` (+ `STAMP_FAILED`): `DRAFT → PDF_GENERATED → STAMPED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` (with `STAMP_FAILED` off the stamp step).

## 6. Unit tests (pyramid base)

- [x] 6.1 FSM transition tests: `PDF_GENERATED → STAMPED → SIGN_REQUESTED` accepted; `STAMP_FAILED` terminal; illegal/out-of-terminal transitions rejected (`STAMP_FAILED → SIGN_REQUESTED`, etc.).
- [x] 6.2 Deterministic serial generator tests: same agreement id → identical serial; distinct ids → distinct serials; BW-series format.
- [x] 6.3 PDFBox composition helper tests: output page count = `1 + draftPages`; serial overlay text present on document pages; input draft bytes unchanged.
- [x] 6.4 Stamp guard tests: empty vs populated `StampInfo` drives procure vs reuse (idempotency); synthetic adapter sets `dutyPaid = false`.
- [x] 6.5 PII/redaction hygiene test: stamping logs/`toString` emit no PDF bytes and no signer PII (only redacted ids/serial).
- [x] 6.6 Defensive-parsing unit tests: the composition helper translates an encrypted PDF, a corrupt/truncated PDF, and a zero-page PDF into a typed stamping-failure (no unmapped exception, no hang).
- [x] 6.7 Geometry unit test: overlay on a landscape / non-A4 page stays within the page media box.
- [x] 6.8 `dutyPaid` invariant unit test: `StampInfo.dutyPaid` equals the `StampResult.dutyPaid` returned by the provider (provider authoritative).

## 7. Integration tests (Testcontainers Postgres + MinIO + WireMock Leegality)

- [x] 7.1 `createSignRequest` auto-stamps: stamped PDF written to `stamped/{agreement-id}.pdf` in MinIO; `agreement.stampInfo` populated (serial, key, `jurisdiction = "KA"`, `dutyPaid = false`, `procuredAt`).
- [x] 7.2 Assert the SigningRequest history shows `STAMPED → SIGN_REQUESTED`, and the document handed to the provider (captured by WireMock) is the **stamped** PDF (stamp page present), not the raw draft.
- [x] 7.3 `V6` migration applies and the app boots under `ddl-auto: validate` (mapping matches migrated stamp columns); `StampInfo` absent from the `GET /api/agreements/{id}` response body.
- [x] 7.4 `ModularityTests` stays green (no new cross-module dependency / cycle).
- [x] 7.5 Malformed-draft integration test: `createSignRequest` for an agreement whose stored draft is unparseable → request ends `STAMP_FAILED`, no provider (WireMock) call, mapped problem+json error.
- [x] 7.6 Reconciliation negative test: a `STAMP_FAILED` row is never selected/advanced by the reconciliation job.

## 8. Verification

- [x] 8.1 Run `./run-tests.sh check` (Testcontainers + WireMock) — all tests + jacoco + osv + spotbugs + ModularityTests green.
- [x] 8.2 Run `./gradlew spotlessApply` and confirm formatting is clean.

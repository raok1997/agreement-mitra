# document-stamping Specification

## Purpose
TBD - created by archiving change stamp-composition. Update Purpose after archive.
## Requirements
### Requirement: Procure a stamp through the StampProvider seam

The system SHALL procure an e-stamp through a vendor-neutral `StampProvider` interface,
parallel to the `EsignProvider` seam. Procurement SHALL return a stamp result carrying a
stamp **serial**, the **composited stamped PDF** bytes, and a **dutyPaid** flag. All
provider-specific details SHALL live behind the interface in a provider adapter package
inside the `signing` module, so a real stamp-procurement adapter (e.g. SHCIL / a
state-portal) can replace the v1 implementation with no caller change. No code outside
the `signing` module SHALL reference the adapter package and `ModularityTests` SHALL
remain green.

The v1 implementation SHALL be a **synthetic** Karnataka ₹100 non-judicial stamp adapter
that pays no real duty: it SHALL set `dutyPaid = false`, generate a synthetic BW-series
serial, and composite a hardcoded stamp template — it SHALL require no external endpoint,
API key, or credential.

#### Scenario: Procurement returns a stamp result

- **WHEN** the system procures a stamp for an agreement's draft PDF
- **THEN** the provider returns a stamp result with a serial, the composited stamped PDF
  bytes, and a `dutyPaid` flag

#### Scenario: v1 synthetic adapter pays no real duty

- **WHEN** the v1 synthetic stamp adapter procures a stamp
- **THEN** the result's `dutyPaid` is `false`, the serial is a synthetic BW-series value,
  and no external service or credential is required

#### Scenario: Module boundaries hold

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references the stamp provider adapter package and
  the verification passes

### Requirement: Deterministic synthetic stamp serial

The synthetic stamp serial SHALL be **deterministically derived from an internal
identifier** (e.g. the agreement id), so it is reproducible and collision-free across
runs. The serial SHALL NOT be drawn from a non-deterministic source (`Math.random` or
wall-clock entropy). The serial format SHALL be an obviously-synthetic Karnataka
BW-series value (e.g. `BW <digits>`) so it is never mistaken for a real procured stamp.

#### Scenario: Same agreement yields the same serial

- **WHEN** a stamp serial is generated twice for the same internal identifier
- **THEN** both generations produce the identical serial

#### Scenario: Different agreements yield different serials

- **WHEN** stamp serials are generated for two distinct agreement identifiers
- **THEN** the two serials differ

### Requirement: Composite the e-stamp onto the agreement PDF

The system SHALL composite the stamp onto the agreement's draft PDF using a JVM PDF
library (Apache PDFBox), producing a new stamped PDF. The composition SHALL **prepend a
hardcoded ₹100 non-judicial stamp-paper template as page 1**, then **overlay a per-page
header** carrying the stamp serial (`Non Judicial Stamp No. BW XXXXX`) on the draft
pages. The resulting stamped PDF SHALL have a page count of `1 + (draft page count)`.
Composition SHALL NOT mutate the stored draft; the draft SHALL remain available
unchanged. The overlay SHALL be positioned relative to **each draft page's own media box**
and respect that page's rotation, so it lands in-frame for any page size or orientation
(A4/Letter/landscape/rotated); the overlay text SHALL use a Standard-14 font (the serial
is ASCII), requiring no bundled font file. Chromium/Playwright SHALL NOT be required for
this step — page-prepend and text overlay are not complex-script *shaping*, so a pure-JVM
PDF library is appropriate (the Chromium-only rule applies to rendering/shaping Indic
scripts, not to overlaying an already-rendered PDF).

#### Scenario: Stamped PDF prepends the stamp page

- **WHEN** a draft PDF of N pages is composited
- **THEN** the stamped PDF has `N + 1` pages, with the ₹100 stamp template as page 1

#### Scenario: Serial overlay appears on the document pages

- **WHEN** a draft PDF is composited with serial `BW 12345`
- **THEN** the stamped PDF's document pages carry the `Non Judicial Stamp No. BW 12345`
  header overlay

#### Scenario: Original draft is not mutated

- **WHEN** a draft PDF is composited into a stamped PDF
- **THEN** the stored draft bytes are unchanged and still retrievable

#### Scenario: Overlay lands in-frame for non-A4 / landscape pages

- **WHEN** a draft whose pages are landscape or a non-A4 size is composited
- **THEN** the serial overlay is positioned relative to each page's media box and remains
  within the page bounds (no off-page or clipped overlay)

### Requirement: Untrusted-draft parsing fails closed

Compositing SHALL parse the user-uploaded, untrusted draft defensively (only its `%PDF-`
magic bytes were validated at upload). Every parse or composition failure —
an **encrypted/password-protected** PDF, a **corrupt or truncated** PDF, a **zero-page**
PDF, or a structurally pathological (deeply nested / huge object-count) PDF — SHALL be
caught and surfaced as a stamping failure, so the signing request transitions to
`STAMP_FAILED` (per `signing-request`) and the provider is not called. It SHALL NOT
produce an unmapped server error, hang the request thread, or exhaust JVM memory. Byte
size is already bounded by the upload ceiling; the parser SHALL run with a bounded
memory/scratch setting so a large-but-legal file cannot exhaust the heap.

#### Scenario: Encrypted draft fails closed

- **WHEN** compositing is attempted on an encrypted/password-protected draft PDF
- **THEN** stamping fails cleanly (no unmapped 500), the signing request goes
  `STAMP_FAILED`, and no provider call is made

#### Scenario: Corrupt or zero-page draft fails closed

- **WHEN** compositing is attempted on a corrupt/truncated or zero-page draft PDF
- **THEN** stamping fails cleanly, the signing request goes `STAMP_FAILED`, and no
  provider call is made

### Requirement: Stamped PDF stored under an agreement-scoped key

The composited stamped PDF SHALL be stored as a blob via the existing object-storage
(`BlobStore`) seam under a key **derived from the internal agreement id**
(`stamped/{agreement-id}.pdf`). The key SHALL NOT be derived from any provider or
external identifier (CR-4 key-hygiene). Stamped PDF bytes SHALL NOT be stored in
PostgreSQL — only the storage key is persisted (on the agreement's stamp info).

#### Scenario: Stamped PDF is written to object storage under the agreement-scoped key

- **WHEN** a stamp is procured for an agreement
- **THEN** the stamped PDF bytes are written to object storage at `stamped/{agreement-id}.pdf`
- **AND** only the key (not the bytes) is persisted in the database

### Requirement: Stamping uses sandbox/dummy data only and logs no PII

Stamping SHALL operate only on the agreement PDF and a synthetic serial; it SHALL NOT
read, write, or log Aadhaar numbers, OTPs, virtual IDs, or signer PII. The synthetic
stamp is dummy data (`dutyPaid = false`) and no real stamp duty SHALL be paid. Logging
around stamping SHALL NOT emit the stamped PDF bytes; only redacted identifiers (e.g. the
agreement id and the serial) MAY be logged.

#### Scenario: No PII or document bytes in stamping logs

- **WHEN** the system logs around stamp procurement and composition
- **THEN** no Aadhaar/OTP/VID/signer PII and no PDF bytes appear in the logs; only
  redacted identifiers are logged

#### Scenario: No real duty is paid

- **WHEN** a stamp is procured in this repo
- **THEN** the stamp is synthetic with `dutyPaid = false` and no external duty payment
  occurs


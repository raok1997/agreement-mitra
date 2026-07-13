## Context

`in.agreementmitra.documents.DocumentRenderer` is a `public interface` with **no
implementation** -- the `documents` module is empty. The guided flow needs to render the
captured agreement (`rich-agreement-capture`) into a viewable rental-agreement document and
use it as the draft that feeds the existing stamp + eSign path (today the draft is a manual
upload via `draft-ingestion`). The architecture already decided **why** the module exists:
complex Indic scripts must be shaped by **headless Chromium**, not a pure-Java PDF library,
with **Noto fonts bundled** (we get Chromium via a Gotenberg service -- see D1 -- rather than
an in-process browser). This CR implements that for **one** bundled
template; the searchable catalog is parked (`CR-2`).

Constraints: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default, `public` only on the module API; keep
`ModularityTests` green; **sandbox + dummy data only**; never log PII or PDF bytes;
Gradle lockfile is regenerated after a dependency change; the OSV + SpotBugs gates must pass.

## Goals / Non-Goals

**Goals:**
- Implement `DocumentRenderer` with headless Chromium: `renderPdf(templateId, data)` produces
  a PDF from a bundled HTML template + a data map.
- Ship **one** rental-agreement template + bundled Noto fonts; render **fully offline**.
- Map an agreement (parties, property, money, dates, duration) to the template data in the
  `signing` module; keep `documents` generic.
- **Preview** the filled document on screen, and **generate** it as the agreement's draft.
- Escape user data and block network in the render (no injection, no exfil/SSRF).

**Non-Goals:**
- No template catalog, selection, or search (`CR-2`, parked) -- exactly one default template.
- No auth/ownership (`mobile-otp-auth`); preview/generate are anonymous like the rest of the
  draft path. No schema change. No change to the signing FSM, stamping, or the eSign flow.
- No `.docx` ingestion, no multi-language template variants, no template authoring UI.

## Decisions

### D1: Render with a Gotenberg service (open source), not an in-process browser

`DocumentRenderer` is implemented by calling **Gotenberg** (open source, MIT --
`gotenberg/gotenberg:8`, a prebuilt container wrapping headless Chromium) over HTTP: the module
renders the template to self-contained HTML and POSTs it to Gotenberg's Chromium
HTML-to-PDF endpoint, receiving the PDF bytes. Gotenberg runs as a **`docker-compose` service**
beside Postgres and MinIO. Rationale: it keeps the **Chromium script-shaping quality** the
architecture requires while putting **zero browser binary, Playwright dependency, or runtime
download in the app** -- the browser is pulled once as an image (the concern raised in review).
**Alternatives rejected:** in-process Playwright/Chromium (a ~150 MB browser to provision in the
app image, plus browser lifecycle + memory in the JVM); pure-Java PDF (mis-shapes Indic
scripts); an external SaaS render API (recurring cost, and it would send PII off-box).

### D2: Server-side HTML templating, one bundled template resource

The template is a bundled HTML resource rendered with a **server-side template engine**
(Thymeleaf -- already Spring-friendly and auto-escaping) into a self-contained HTML string,
which Chromium then prints. `templateId` selects the template; with the catalog parked there
is exactly one -- a `rental-agreement` default. **No client-side JavaScript** runs in the
template (data is bound server-side), so Chromium only lays out static, trusted HTML.
**Alternative rejected:** hand-built PDF layout in code -- unmaintainable for a legal
document; a JS-driven template -- unnecessary attack surface.

### D3: `documents` stays generic; `signing` owns the agreement-to-data mapping

`documents` knows only `renderPdf(templateId, Map<String,Object> data)`. The **`signing`
module** builds the data map from an `Agreement` (owners and tenants grouped by role, each
with full name / father's name / current address; property address; monthly rent; security
deposit; start and end dates; derived duration in months) and calls the renderer. `signing`
depends only on the **public `DocumentRenderer` interface** exposed as the `documents`
module's named interface -- no `signing` to `documents`-internal dependency, so
`ModularityTests` stays green. **Alternative rejected:** putting agreement knowledge inside
`documents` -- couples the render module to the domain and breaks its reuse.

### D4: Self-contained HTML; Gotenberg's outbound network denied

The render loads **no external resource**: CSS is inlined and the fonts live in the Gotenberg
image (or are sent as render assets), and the HTML sent to Gotenberg references **only**
bundled/attached files -- no CDN, no remote URL. Gotenberg is configured to **deny outbound
network** for its Chromium (deny-list all URLs), so a crafted party field (or a future template
edit) cannot fetch a remote URL (SSRF) or exfiltrate the rendered PII. The app-to-Gotenberg
call itself stays on the local docker network. **Alternative rejected:** allowing Gotenberg
network access for convenience -- reintroduces the exfil/SSRF surface.

### D5: User data is untrusted -- escape it; the template is trusted

Party names, father's names, and addresses are **user-supplied and untrusted**. Thymeleaf's
default **HTML-escaping** is used for every data binding, so a value like `<b>` or a script
tag renders as literal text, never as markup -- no content injection into the document, and
no active content for Chromium to execute. The template file itself is a trusted, reviewed
resource. **Alternative rejected:** unescaped/raw binding -- injection risk.

### D6: Preview renders on demand; generate stores the draft

Two operations, both on the agreement API in `signing`:
- `GET /api/agreements/{id}/preview` -- render the filled template and return the **PDF
  inline** (`Content-Type: application/pdf`, `Content-Disposition: inline`,
  `Cache-Control: no-store`) for on-screen display. **Not stored.**
- `POST /api/agreements/{id}/document` -- render and **store the PDF as the agreement's draft**
  through the existing draft path (`DraftService`/`attachDraft`), returning the agreement id.
  This makes the template-rendered document the draft that stamping + eSign consume, replacing
  the manual upload for the guided flow.

Both are anonymous (capability read of the agreement, consistent with the current draft
path). **Alternative rejected:** always store on preview -- wasteful and pins PII for every
keystroke-preview; keep preview ephemeral.

### D7: Bundle Noto fonts in the Gotenberg image

Noto Sans (Latin) + the Indic coverage the template needs (e.g. Noto Sans Devanagari) are baked
into a **thin custom Gotenberg image** (`FROM gotenberg/gotenberg:8`, copy the Noto faces into
the font path, refresh the font cache), or sent as render assets. This gives Gotenberg's
Chromium the glyphs offline -- the reason Chromium (via Gotenberg) is required over a Java PDF
lib. Noto is OFL; the license travels with the bundled files.

### D8: The app is a stateless HTTP client; Gotenberg owns the browser lifecycle

The app holds **no browser** -- each render is a single HTTP POST to Gotenberg, which manages
Chromium internally (process reuse, isolation) and can be scaled independently of the app. The
app bounds concurrent renders with a small guard and a client timeout. **Alternative rejected:**
managing a browser (or a pool) inside the JVM -- exactly the in-process complexity Gotenberg
removes.

### D9: Render is a synchronous, bounded HTTP call

Preview/generate render **synchronously** via one HTTP call to Gotenberg (a few seconds is
acceptable for an on-demand preview; this is not the eSign path, so the "never block on a
signature" rule does not apply). The client applies a **timeout** and a response-size guard; a
Gotenberg error or timeout is a clean `5xx` problem response, never a partial draft.
**Alternative rejected:** async render + polling -- overkill for an interactive preview.

### D10: Gotenberg provisioning -- pulled once as an image, no runtime download

**No licensing cost -- all components are open source:** Gotenberg (MIT), the Chromium it wraps
(BSD), and the Noto fonts (SIL OFL) are free. Gotenberg is provisioned exactly like Postgres or
MinIO -- its Docker image (the thin Noto-font variant) is **pulled once** and run as a compose
service; **nothing is downloaded at app runtime**, and no browser binary ships in the app
image. Local dev gets it via `docker compose up -d`; other environments pull the same image.
Chromium runs headless **inside Gotenberg** (its image already handles the sandbox flags), so
the app carries none of that. Avoiding a pure-Java PDF route also sidesteps iText 7's
AGPL/commercial licensing.

## Risks / Trade-offs

- **Extra service dependency** -- rendering now needs the Gotenberg container running; if it is
  down, preview/generate fail with a clean `5xx`. Mitigated: it is a compose service like
  Postgres/MinIO (same operational model), and integration tests that need it **skip cleanly**
  when Docker is absent (the existing Testcontainers pattern), unit tests do not.
- **Render latency + memory live in Gotenberg** -- Chromium is resource-heavy, but it runs in
  Gotenberg (scaled independently), not the JVM; the app only bounds concurrency + timeout
  (D8/D9).
- **HTML injection** -- neutralized by default escaping (D5); the template is the only trusted
  markup.
- **Network-deny correctness** -- if Gotenberg's outbound-deny is misconfigured, an external
  fetch could slip through; covered by an integration test asserting a template referencing a
  remote URL still renders with the remote content absent.
- **Font coverage** -- a script without a bundled Noto face renders tofu; mitigated by
  bundling the faces the template needs and a rendering smoke test with Indic sample text.
- **OSV/SAST gates** -- the new deps must pass; regenerate the lockfile and triage any
  finding via the documented suppression process (reason + expiry), never a blanket ignore.

## Migration Plan

**No database migration.** The generated draft reuses the existing `agreement.draft_pdf_key`
and object storage; no schema change. The changes are: a new **Gotenberg `docker-compose`
service** (a thin Noto-font image), additive backend dependencies (an HTTP client + the
template engine), and the bundled template/font resources; regenerate the Gradle lockfile and
re-run the gates.

## Open Questions

- **Preview format** -- return the **PDF** (highest fidelity to the signed artifact, proposed)
  or a lighter **HTML preview** (faster, but can diverge from the PDF)? Proposing PDF.
- **Template engine** -- **Thymeleaf** (proposed; Spring-native, auto-escaping) vs a lighter
  engine (Mustache). Confirm.
- **Indic font scope** -- which Noto faces to bundle now (Latin + Devanagari proposed); add
  more as templates for other states arrive.
- **Draft regeneration** (resolved) -- `POST .../document` **overwrites** the stored draft
  while no signing request exists, and is **blocked once signing has started** (the draft is
  locked). Confirmed.

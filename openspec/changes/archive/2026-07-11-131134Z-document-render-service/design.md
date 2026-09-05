## Context

`in.agreementmitra.documents.DocumentRenderer` is a `public interface` with no implementation.
This increment implements it. The design decisions are inherited **verbatim** from the retired
`agreement-document-render` proposal (D1..D10); only the ones this increment realizes are restated
here. The agreement-to-data mapping and the HTTP endpoints are deliberately deferred to CR-3b/CR-3c
so this increment ships a small, reviewable, domain-agnostic renderer.

Constraints: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default, `public` only on the module API; keep `ModularityTests`
green; **sandbox + dummy data only**; never log PII or PDF bytes; Gradle lockfile regenerated after
a dependency change; OSV + SpotBugs gates must pass.

## Goals / Non-Goals

**Goals:**
- Implement `DocumentRenderer.renderPdf(templateId, data)`: a bundled HTML template + a data map ->
  PDF bytes, via a Gotenberg (headless Chromium) HTTP call.
- Ship **one** `rental-agreement` template + bundled Noto fonts; render **fully offline**.
- Escape user data (no injection) and block network in the render (no exfil/SSRF).
- Expose `DocumentRenderer` as the `documents` module's public named interface.

**Non-Goals (this increment):**
- No agreement-to-data mapping (CR-3b). No `preview` or `generate-document` endpoint (CR-3b/CR-3c).
  No frontend. No template catalog/selection/search (CR-2, parked) -- exactly one default template.
- No schema change; no change to the signing FSM, stamping, or the eSign flow.

## Decisions (inherited from the superseded proposal)

### D1: Render with a Gotenberg service, not an in-process browser
`DocumentRenderer` calls **Gotenberg** (`gotenberg/gotenberg:8`, MIT -- a prebuilt container
wrapping headless Chromium) over HTTP. Gotenberg runs as a `docker-compose` service beside
Postgres and MinIO. This keeps Chromium's script-shaping quality while putting **zero browser
binary, Playwright dependency, or runtime download in the app**. Rejected: in-process
Playwright/Chromium (a ~150 MB browser in the app image + JVM lifecycle/memory); pure-Java PDF
(mis-shapes Indic scripts); external SaaS render (recurring cost, sends PII off-box).

### D2: Server-side HTML templating, one bundled template resource
The template is a bundled HTML resource rendered with **Thymeleaf** (Spring-friendly,
auto-escaping) into a self-contained HTML string, which Chromium prints. `templateId` selects it;
with the catalog parked there is exactly one -- a `rental-agreement` default. **No client-side
JavaScript** in the template. Rejected: hand-built PDF layout in code; a JS-driven template.

### D4: Self-contained HTML; Gotenberg's outbound network denied
The render loads **no external resource**: CSS is inlined and the Noto fonts live in the Gotenberg
image; the HTML references only bundled/attached files. Gotenberg's Chromium is configured to
**deny outbound network**, so a crafted data value (or a future template edit) cannot fetch a
remote URL (SSRF) or exfiltrate the rendered content. The app-to-Gotenberg call stays on the local
docker network. Rejected: allowing Gotenberg network access for convenience.

### D5: User data is untrusted -- escape it; the template is trusted
Every data binding uses Thymeleaf's default **HTML-escaping**, so a value like `<script>` renders
as literal text, never as markup -- no injection, no active content for Chromium. The template file
itself is the only trusted markup. Rejected: unescaped/raw binding.

### D7: Bundle Noto fonts in the Gotenberg image
Noto Sans (Latin) + Noto Sans Devanagari (the Indic coverage the template needs) are baked into a
thin custom Gotenberg image (`FROM gotenberg/gotenberg:8`, copy the Noto faces into the font path,
refresh the font cache). This gives Chromium the glyphs offline. Noto is SIL OFL; the license
travels with the bundled files.

### D8/D9: Stateless HTTP client, synchronous bounded call
The app holds **no browser** -- each render is a single HTTP POST to Gotenberg, which owns Chromium
internally. The render is **synchronous** (a preview is not the eSign path, so "never block on a
signature" does not apply); the client applies a **timeout** and a small concurrency guard, and a
Gotenberg error/timeout surfaces as a clean failure, never a partial document.

### D10: Gotenberg provisioning -- pulled once as an image, no runtime download
All components are open source (Gotenberg MIT, Chromium BSD, Noto OFL -- no licensing cost).
Provisioned exactly like Postgres/MinIO: the image is pulled once and run as a compose service;
**nothing is downloaded at app runtime**, no browser binary ships in the app.

## Component shape (this increment)

- `documents/rental-agreement.html` (classpath resource) -- Thymeleaf template, inlined CSS,
  `@font-face` -> bundled Noto faces, no external URL.
- `TemplateEngine` wiring -- a package-private Thymeleaf engine bound to the classpath template(s);
  resolves `templateId` -> resource, **unknown id -> a thrown error** (no silent empty doc).
- `GotenbergClient` (package-private) -- a Spring `RestClient` to `${GOTENBERG_URL}`; POSTs the
  self-contained HTML (multipart, `index.html`) to the Chromium HTML-to-PDF route with the
  outbound-deny render option; returns PDF bytes; timeout + concurrency guard; never logs HTML/PDF.
- `GotenbergDocumentRenderer implements DocumentRenderer` (package-private `@Component`) -- resolve
  template -> render HTML (escaped) -> `GotenbergClient` -> bytes.
- `package-info` -- declare `DocumentRenderer` as the module's **named interface** so `signing`
  (CR-3b/CR-3c) can depend on it Modulith-cleanly.
- `GotenbergProperties` (`@ConfigurationProperties("gotenberg")`) + config wiring, mirroring the
  Leegality/Storage config pattern; `GOTENBERG_URL` env var with a local default.

## Risks / Trade-offs

- **Extra service dependency** -- rendering needs the Gotenberg container; integration tests that
  need it **skip cleanly** when Docker is absent (existing Testcontainers pattern); unit tests do
  not need it.
- **Network-deny correctness** -- if the outbound-deny is misconfigured an external fetch could
  slip through; covered by an integration test asserting a template referencing a remote URL still
  renders with the remote content absent.
- **Font coverage** -- a script without a bundled Noto face renders tofu; mitigated by bundling the
  needed faces + an Indic-sample smoke test.
- **OSV/SAST gates** -- the new Thymeleaf dep must pass; regenerate the lockfile and triage any
  finding via the documented suppression process (reason + expiry), never a blanket ignore.

## Migration Plan

**No database migration.** Changes are: a new Gotenberg `docker-compose` service (thin Noto image),
one additive backend dependency (Thymeleaf; `RestClient` ships with the existing web starter), and
the bundled template/font resources. Regenerate the Gradle lockfile and re-run the gates.

## Open Questions (resolved for this increment)

- **Template engine** -- Thymeleaf (Spring-native, auto-escaping). Confirmed.
- **Indic font scope** -- Latin + Devanagari now; more faces as other-state templates arrive.
- **Preview format / draft regeneration** -- out of scope here (CR-3b / CR-3c).

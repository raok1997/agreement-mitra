## Why

The `documents` module's `DocumentRenderer` is a `public interface` with **no implementation** --
the module is empty. Before the guided flow can preview a captured agreement or feed a generated
draft into stamping/eSign, the platform needs a working **template -> PDF** renderer that shapes
complex Indic scripts correctly (the architecture's reason the `documents` module exists at all).

This is the **first of three** increments that decompose the retired `agreement-document-render`
proposal (see `archive/2026-07-11-superseded-agreement-document-render/`). It ships the **render
capability only** -- no agreement wiring, no HTTP endpoint, no frontend. Those arrive in CR-3b
(preview) and CR-3c (generate-as-draft), each building on this renderer.

## What Changes

- **Implement `DocumentRenderer.renderPdf(templateId, data)`** in the `documents` module as a thin
  **Gotenberg** HTTP client (Spring `RestClient` to `GOTENBERG_URL`): render a bundled HTML
  template + a data map to **self-contained HTML** (server-side Thymeleaf, auto-escaping ON), POST
  it to Gotenberg's Chromium HTML-to-PDF endpoint, and return the PDF bytes. **No Playwright, no
  browser binary in the app, nothing downloaded at runtime.**
- **Add a Gotenberg service to `docker-compose`** -- a thin image (`FROM gotenberg/gotenberg:8`)
  carrying **Noto Sans + Noto Sans Devanagari** so Chromium has the glyphs offline. Pulled once
  like Postgres/MinIO; comes up with `docker compose up -d`.
- **Bundle exactly one** `rental-agreement.html` Thymeleaf template (inlined CSS, `@font-face` to
  the bundled Noto faces, no external URL). The catalog is parked (CR-2); `templateId` selects the
  one default, and an **unknown id is an error**, not a silent empty document.
- **Self-contained + network-denied render**: the HTML references only bundled resources, and
  Gotenberg is configured to **deny outbound network** for its Chromium (SSRF / exfil guard). A
  crafted data value or template edit cannot fetch a remote URL.
- **Escape all data**: Thymeleaf default HTML-escaping neutralizes markup in a data value (it
  renders as literal text), so untrusted party data cannot inject document structure.
- **Expose `DocumentRenderer` as the `documents` module's public named interface**; keep the impl,
  the Gotenberg client, and template internals package-private. `ModularityTests` stays green.

## Capabilities

### New Capabilities
- `document-rendering`: turn a template id + a data map into a PDF via headless Chromium (a
  Gotenberg service) with bundled fonts and fully offline rendering -- the concrete
  `DocumentRenderer`, the single bundled rental-agreement template, escaping, and the
  network-deny guard. (The agreement-to-data mapping, preview, and generate-draft endpoints are
  **out of scope** here -- CR-3b / CR-3c.)

## Impact

- **`documents` module**: implement `DocumentRenderer` as a Gotenberg HTTP client; add the
  Thymeleaf template engine; bundle the `rental-agreement` template. Expose `DocumentRenderer` as
  the module's public named interface (`package-info`). `ModularityTests` stays green.
- **Infra + dependencies**: add the Gotenberg compose service (thin Noto image); add Spring
  `RestClient` (already on the `web` starter) + Thymeleaf to the backend; regenerate the Gradle
  lockfile and re-run the OSV/SpotBugs gates. `GOTENBERG_URL` env var (default the local compose
  URL), like `S3_ENDPOINT`.
- **No** change to: the signing FSM, the agreement API, `EsignProvider`/webhook flow, stamping, or
  the DB schema. No frontend change.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No new PII flow in this increment.**
  The renderer is domain-agnostic -- it renders whatever generic data map it is handed; in this CR
  it is exercised only with **dummy test data** (no agreement wiring yet). No Aadhaar number, OTP,
  virtual id, biometric, or government identifier is added; no secrets are introduced
  (`GOTENBERG_URL` is a non-secret local endpoint).
- **How redacted/secured?** The render pipeline established here **never logs** the composed HTML
  or the PDF bytes. Data is **HTML-escaped** before templating (no content injection), and the
  Chromium render runs **offline with outbound network denied** (a crafted field cannot exfiltrate
  data or fetch a remote resource -- SSRF/exfil guard). These are the guards CR-3b/CR-3c rely on
  once real party PII flows through.
- **Sandbox + dummy data only?** Preserved -- local rendering of dummy data; no live provider, no
  real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- rendering is independent of signing and
  adds no state or transition.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.

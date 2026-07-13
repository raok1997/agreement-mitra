## 1. Infra + dependencies

- [ ] 1.1 Add a **Gotenberg service to `docker-compose.yml`** (a thin image built
  `FROM gotenberg/gotenberg:8` that copies the Noto font faces into the font path and refreshes
  the font cache), exposed on its port; wire a `GOTENBERG_URL` env var (default the local
  compose URL) like the existing S3 settings. It comes up with the existing `docker compose up
  -d` (and `daily-start.sh`).
- [ ] 1.2 Add an HTTP client (Spring `RestClient`) and the HTML template engine (Thymeleaf) to
  the backend build; regenerate the Gradle lockfile (`./gradlew dependencies --write-locks`)
  and confirm the OSV + SpotBugs gates pass (triage any finding with a reason + expiry). **No
  Playwright, no browser binary in the app.**

## 2. Rendering engine (`in.agreementmitra.documents`)

- [ ] 2.1 Bundle the single rental-agreement template as an HTML resource
  (`documents/templates/rental-agreement.html`) using Thymeleaf bindings, with inlined CSS and
  `@font-face` pointing at the Noto faces available in the Gotenberg image (or sent as assets).
  Record the Noto OFL license. No external URLs.
- [ ] 2.2 Add a package-private Gotenberg client (Spring `RestClient` to `GOTENBERG_URL`) that
  POSTs a self-contained HTML document (plus any font/asset files) to Gotenberg's Chromium
  HTML-to-PDF endpoint and returns the PDF bytes; apply a request timeout and a small
  concurrency guard. Configure Gotenberg to **deny outbound network**.
- [ ] 2.3 Implement `DocumentRenderer.renderPdf(templateId, data)`: resolve the bundled
  template (unknown id -> error), render it to HTML with Thymeleaf (**auto-escaping on**), send
  it to the Gotenberg client, and return the PDF bytes. Never log the HTML or PDF.
- [ ] 2.4 Expose `DocumentRenderer` as the `documents` module's **public named interface**
  (update `package-info`); keep the implementation, the Gotenberg client, and template
  internals package-private. `ModularityTests` must stay green.

## 3. Agreement mapping + endpoints (`in.agreementmitra.signing`)

- [ ] 3.1 Add an agreement-to-template-data mapper (in `signing`): from an `Agreement`, build
  the data map -- owners and tenants grouped by role, each with full name, father's name, and
  current address; property address; monthly rent; security deposit; start and end dates; and
  the derived duration in months. `signing` depends only on the public `DocumentRenderer`.
- [ ] 3.2 `AgreementController` (`signing.api`): add `GET /api/agreements/{id}/preview` --
  load the agreement (404 if unknown), map + render, and return the PDF **inline**
  (`application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`); not stored.
- [ ] 3.3 `AgreementController`: add `POST /api/agreements/{id}/document` -- map + render, then
  store the PDF as the agreement's draft via the existing `DraftService`/`attachDraft`;
  respond `200`. Reject (`409`) when a signing request already exists (draft locked);
  overwrite any prior draft otherwise. `404` for an unknown agreement.

## 4. Security wiring

- [ ] 4.1 Permit the two new agreement sub-paths in `SecurityConfig` consistently with the
  existing anonymous draft path (`GET /api/agreements/*/preview`,
  `POST /api/agreements/*/document`) -- method-and-path-scoped, not a wildcard -- and keep the
  in-code TEMPORARY/sandbox comment style. (Ownership scoping arrives with `mobile-otp-auth`.)

## 5. Frontend (`frontend/src`)

- [ ] 5.1 Add a **Preview** panel to the capture screen that embeds the rendered PDF from
  `GET /api/agreements/{id}/preview` (object/iframe), refreshed on demand, and a **"use this
  document"** action that calls `POST /api/agreements/{id}/document` and proceeds toward
  signing. Keep API calls in `src/api/`. Still anonymous.

## 6. Tests -- unit (no Spring context, no browser)

- [ ] 6.1 Agreement-to-data mapper: an aggregate with owners + tenants maps to a data map
  with parties grouped by role and all fields (names, father's name, address, money, dates,
  duration) present and correctly placed; no PII is dropped or mislabelled.
- [ ] 6.2 Template HTML assembly (engine-level, no Chromium): binding a data value containing
  angle-bracket markup produces **escaped** HTML (the markup appears as text, not tags);
  the unknown-template-id path raises the expected error.

## 7. Tests -- integration (Gotenberg via Testcontainers; skips cleanly if Docker absent)

- [ ] 7.1 Render smoke: with a Gotenberg Testcontainer, `DocumentRenderer` renders the
  rental-agreement template with a dummy data map to a **valid PDF** (magic-byte check,
  non-trivial size); Indic sample text renders with the bundled font (no tofu). Skips cleanly
  when Docker/Gotenberg is unavailable.
- [ ] 7.2 Network-deny enforcement: a template referencing a remote URL still renders to a PDF
  with the remote content absent (Gotenberg's outbound-deny holds).
- [ ] 7.3 Preview endpoint: `GET /api/agreements/{id}/preview` returns `200` with
  `application/pdf`, `Content-Disposition: inline`, and `Cache-Control: no-store`; `404` for
  an unknown id; nothing is persisted.
- [ ] 7.4 Generate endpoint: `POST /api/agreements/{id}/document` stores the rendered PDF as
  the draft (a subsequent signing request finds a draft present); a second generate overwrites
  while no signing request exists, and is rejected (`409`) once one exists. `ModularityTests`
  stays green (signing depends only on the `documents` public interface).
- [ ] 7.5 Log hygiene: across a preview + generate, assert no log line contains the PDF bytes
  or composed party details.

## 8. Frontend tests (Vitest)

- [ ] 8.1 Component test: the Preview panel requests the preview URL and embeds the returned
  PDF; the "use this document" action posts to the generate endpoint and advances the flow
  (mocked fetch; assert no PII is logged to the console).

## 9. Verify

- [ ] 9.1 Backend: run `./run-tests.sh` (or `./gradlew check` with Docker for the Gotenberg
  Testcontainer) -- full suite green, including `securityScan`, the JaCoCo gate, and
  `ModularityTests`.
- [ ] 9.2 Frontend: `npm run test` and `npm run security:scan` green.
- [ ] 9.3 Manual: run the daily stack, draft an agreement with dummy data (including an Indic
  sample name), open the Preview and confirm the filled template renders correctly on screen,
  then "use this document" and confirm a signing request finds the generated draft.

## 1. Infra + dependencies

- [x] 1.1 Add a **Gotenberg service to `docker-compose.yml`**: a thin image built
  `FROM gotenberg/gotenberg:8` (`docker/gotenberg/Dockerfile`) that installs `fonts-noto-core`
  (Noto Sans + Noto Sans Devanagari) and refreshes the font cache, exposed on port 3000, with
  `CHROMIUM_DENY_PUBLIC_IPS`/`CHROMIUM_DENY_PRIVATE_IPS` set. Comes up with `docker compose up -d`.
- [x] 1.2 Add **Thymeleaf** to the backend build (used plain `org.thymeleaf:thymeleaf`, not the
  MVC-view starter -- the engine is built directly, so no view auto-config). Spring `RestClient`
  ships with the existing web starter (no new HTTP-client dep). Regenerated the Gradle lockfile
  (thymeleaf 3.1.5 + ognl 3.3.4 + attoparser + unbescape now locked). **No Playwright/browser.**
  Note: `osvScan` not run -- `osv-scanner` not installed on this box (fail-closed gate); owed.
- [x] 1.3 Add `GOTENBERG_URL` config: `gotenberg.url: ${GOTENBERG_URL:http://localhost:3000}` in
  `application.yml`, bound by `GotenbergProperties` (also `max-concurrent-renders`, `request-timeout`).

## 2. Rendering engine (`in.agreementmitra.documents`)

- [x] 2.1 Bundle `documents/rental-agreement.html` (classpath resource) with Thymeleaf `th:text`
  bindings, inlined CSS, and system Noto font families (no `@font-face` URL -- fully offline). Noto
  OFL noted in the Dockerfile + template comments. No external URLs.
- [x] 2.2 `TemplateAssembler` (package-private): a Thymeleaf `TemplateEngine` (HTML mode,
  auto-escaping ON) resolving `documents/<id>.html`; renders to a self-contained HTML string;
  unknown/null `templateId` throws `IllegalArgumentException` (no silent empty document).
- [x] 2.3 `GotenbergClient` (package-private): Spring `RestClient` to `${gotenberg.url}`; POSTs the
  self-contained HTML as multipart `index.html` to `/forms/chromium/convert/html` (A4); `Semaphore`
  concurrency guard + client read timeout; empty/failed render -> `DocumentRenderException`. Never
  logs HTML/PDF.
- [x] 2.4 `GotenbergDocumentRenderer implements DocumentRenderer` (package-private `@Component`):
  assemble -> `GotenbergClient` -> PDF bytes.
- [x] 2.5 `DocumentRenderer` (+ `DocumentRenderException`) are the module's public API; impl,
  client, assembler, template are package-private. Updated `package-info` (Playwright -> Gotenberg).
  `ModularityTests` stays green (verified).

## 3. Tests -- unit (no Spring context, no browser)

- [x] 3.1 `TemplateAssemblerTest`: supplied fields appear in the HTML; a value with angle-bracket
  markup is **escaped** (`&lt;script&gt;` / `&lt;b&gt;`, raw tags absent).
- [x] 3.2 `TemplateAssemblerTest`: unknown-id and null-id both raise `IllegalArgumentException`.

## 4. Tests -- integration (Gotenberg via Testcontainers; skips cleanly if Docker absent)

- [x] 4.1 `GotenbergDocumentRendererIntegrationTest.rendersRentalAgreementToValidPdf`: builds the
  `docker/gotenberg` image, renders to a valid PDF (`%PDF-` magic + non-trivial size). Ran (green).
- [x] 4.2 `...rendersDevanagariSampleWithBundledFont`: Devanagari sample renders to a valid PDF via
  the bundled Noto face. Ran (green).
- [x] 4.3 `...remoteReferenceTriggersNoOutboundRequestAndStillRenders`: HTML referencing remote +
  link-local URLs still renders (outbound-deny holds). Ran (green).

## 5. Verify

- [x] 5.1 Backend: full `test` + `spotbugsMain` (SAST) + `jacocoTestCoverageVerification` all green,
  including `ModularityTests` and all existing Postgres/MinIO Testcontainers tests -- no regression.
  (`osvScan` owed: `osv-scanner` not installed; ran gradle directly with Ryuk disabled -- see the
  Windows Testcontainers memory.)
- [x] 5.2 Manual: `docker compose build gotenberg && docker compose up -d gotenberg` -> health 200;
  a direct render of a Latin+Devanagari sample (with a remote `<img>`) returned a valid 24 KB PDF
  (`%PDF-1.4`) with the remote resource blocked. Sample PDF saved for on-screen eyeballing.

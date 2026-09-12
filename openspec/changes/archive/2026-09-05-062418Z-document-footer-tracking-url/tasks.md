> **Windows test notes (project memory):** run gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
> and `-Duser.timezone=Asia/Kolkata`; write all files in pure ASCII (the PII/secret guard fails closed
> on non-ASCII). Integration tests use Testcontainers (`@Testcontainers(disabledWithoutDocker = true)`)
> and need a running Docker daemon; without Docker they skip cleanly.
>
> **Supersedes the interim implementation:** an earlier pass put the tracking number + URL in the
> PDF-only Chromium furniture (`GotenbergClient.footerHtml` three cells; `DocumentProjectionService`
> PREVIEW marker for `previewPdf`). This plan **moves the reference + URL into the compiled body** and
> reduces furniture to page numbers, so that interim code is reworked (not extended): `footerHtml`
> becomes page-numbers-only; the body provenance line replaces the furniture reference/URL.
>
> **Coordination:** extends/reshapes the footer introduced by the active `agreement-execution-block` CR;
> whichever archives second reconciles the footer requirement.
>
> **Applied notes (2026-07-14):**
> - Provenance line lives in `TemplateCompiler` (new 6-arg `compile` overload; the 4-arg delegates with
>   nulls so existing compiler tests are unchanged). `DocumentProjectionService` injects the now-public
>   `DocumentFooterProperties`, resolves the reference (documentReference, else the PREVIEW marker) + URL,
>   and passes both into `compile`. `GotenbergClient.footerHtml()` is page-numbers-only and always
>   applied; the `HtmlPdfRenderer` reference overload was removed. `Agreement.trackingNumber()` is the
>   single derivation, reused by `AgreementDocumentService` and `AgreementService.toResponse`.
> - Verified: backend unit (`GotenbergClientFooterTest`, `DocumentProjectionServiceTest`,
>   `AgreementDocumentServiceTest`, `TemplateCompilerTest`, `SigningRequestServiceTest`) + integration
>   (`DocumentProjectionApiIntegrationTest` 11/11, incl. two HTML-body tests proving the provenance line
>   is in the PREVIEW; `ModularityTests`, `AgreementApiIntegrationTest`, `AgreementPreviewIntegrationTest`)
>   all green. Frontend: new `CaptureForm` test + `documentPreview` client + `vue-tsc` typecheck green.
> - **Pre-existing, unrelated:** two `CaptureForm.test.ts` tests (`required-remaining`) fail on the clean
>   committed tree too (the template has no such testid) -- not caused by this CR.
> - No DB migration; no `gradle.lockfile` change.
>
> **Applied notes (2026-07-14, PDF fix after review):** a rendered multi-page PDF showed two defects --
> the body provenance line **orphaned onto its own last page** (trailing flow content), and the footer
> **page total (`of Y`) rendered blank**. Fixed by the **hybrid** now in the design (D3/D4):
> - Provenance is a **screen-only** body line (`.doc-provenance` = `display:none`, shown under
>   `@media screen`) for the preview; the PDF render sets `emulatedMediaType=print` (hides it -> no
>   orphan) and stamps the reference + URL as **Gotenberg footer furniture** in the reserved margin,
>   per page (clear of content). So `HtmlPdfRenderer.toPdf(html, reference)` and the `GotenbergClient`
>   furniture were restored; `DocumentFooterProperties` is injected into `GotenbergClient` again.
> - The footer uses a **table** layout, not flex -- Chromium reliably fills `.totalPages` in a table,
>   so `Page X of Y` now shows the total.
> - Verified with real Gotenberg: `DocumentProjectionApiIntegrationTest.idBound...` asserts the
>   reference appears **once per page** (furniture; the screen-only body line is hidden -> no orphan)
>   and `Page 1 of <pages>` (the total renders). Full backend suite (unit + integration +
>   `ModularityTests` + API + preview) green.

## 1. Provenance line in the compiled body (package `documents.template`, package-private)

- [x] 1.1 Add two resolved inputs to `TemplateCompiler.compile(...)` -- a **reference** string and a
  **platform URL** string -- and emit a system-owned **provenance line at the document foot**:
  `{reference} . {platformUrl}`, each part **HTML-escaped**, each **omitted when blank**. It is
  system-owned markup (like the signature block); it reads no clock/config (pure function).
- [x] 1.2 In `DocumentProjectionService`, resolve the **platform URL from configuration** once and pass
  it, plus the request's `documentReference`, into `compile` on the single compile path -- so
  `previewHtml`, `previewPdf`, and `generate` all render the identical provenance line (parity).
- [x] 1.3 **Unit test:** the compiled HTML carries the provenance line at the foot with the escaped
  reference and escaped URL; a hostile reference/URL renders as literal text (no injection); a blank
  reference or blank URL omits that part; the compiler reads no config/clock.
- [x] 1.4 **Unit test (parity):** `previewHtml` equals the HTML handed to the PDF renderer for the same
  inputs, provenance line included (the line is body content shared by both faces).

## 2. Tracking number on the saved-agreement render (package `in.agreementmitra.signing.agreement`)

- [x] 2.1 Add a package-private helper deriving `AM-<LAST6>-<DDMMYY>` (uppercased last six hex of the
  agreement UUID + the start date as zero-padded `ddMMyy`). In `AgreementDocumentService.render`, pass
  it as `documentReference`. Document that it is display-only and the full UUID stays the audit tie.
- [x] 2.2 **Unit test:** derivation -- last six hex uppercased; `ddMMyy` zero-pads single-digit day
  and month (1 Jul 2026 -> `010726`); id ending `a5e4d7` + 1 Jul 2026 -> `AM-A5E4D7-010726`.

## 3. Furniture reduced to page numbers only (package `in.agreementmitra.documents`, package-private)

- [x] 3.1 Rework `GotenbergClient.footerHtml` to stamp **page numbers only** (`Page <pageNumber> of
  <totalPages>`), applied on **every** PDF render. Remove the reference/URL cells (now body content).
  Keep `DocumentFooterProperties` (`documents.footer.platform-url`, blank default) for the URL the
  projection service resolves; add the property to `application.yml`
  (`${DOCUMENT_FOOTER_PLATFORM_URL:agreementmitra.com}`).
- [x] 3.2 **Unit test:** the furniture footer contains the `.pageNumber`/`.totalPages` placeholders and
  no reference/URL cell.

## 4. Expose the tracking number to the client (package `in.agreementmitra.signing.api`)

- [x] 4.1 Add `trackingNumber` to `AgreementResponse`, populated by `AgreementService.toResponse` from
  the same derivation helper (derived, not stored). Keep the raw `id` as the canonical identifier.
- [x] 4.2 **Unit test:** `toResponse` sets `trackingNumber` to `AM-<LAST6>-<DDMMYY>` for a known
  agreement; the raw `id` is unchanged.

## 5. Frontend: show the number after save + feed it into the preview (package `frontend/src`)

- [x] 5.1 Carry `trackingNumber` on `AgreementView`/`createAgreement` (`api/client.ts`). In
  `CaptureForm.vue`, after a successful save **display** the tracking number on the save confirmation and
  **pass it into the preview request** (as `documentReference`) so the post-save preview body shows the
  real number; pre-save the preview passes the `PREVIEW - NOT FOR EXECUTION` marker (URL + marker, no
  number).
- [x] 5.2 **Frontend unit test (vitest):** after save the tracking number is shown and the subsequent
  preview request carries it; before save the preview carries the marker, not a number.

## 6. Integration + module boundary

- [x] 6.1 **Integration test:** the stateless preview HTML carries the provenance line (URL + marker),
  and a saved-agreement render (HTML + PDF) carries the derived `AM-<LAST6>-<DDMMYY>` + URL in the
  body and `Page X of Y` in the PDF furniture. Extract rendered-PDF text (PDFBox) for the PDF assertions;
  assert on the compiled HTML for the body/preview assertions.
- [x] 6.2 **Integration test:** `ModularityTests` stays green -- the compiler + projection service stay
  package-private in `documents.template`, `documents` holds no brand literal (URL is app config), and
  the public `documents.api` surface is unchanged.

## 7. Wrap-up

- [x] 7.1 `./gradlew spotlessApply` green; run the `documents.*` + `signing.*` suites + `ModularityTests`
  (`TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`) and `npm run test`/`lint` for the
  frontend. Confirm **no new dependency and no `gradle.lockfile` change**.
- [x] 7.2 Confirm the invariants: provenance line in preview AND PDF (parity, byte-for-byte body);
  every value HTML-escaped; the URL inert/offline and omitted when blank; page numbers on every PDF page;
  the full UUID unchanged as the audit reference; `trackingNumber` derived, not stored; no DB migration;
  the stateless preview still persists nothing and stays `no-store`; nothing logged.
- [x] 7.3 Verified end-to-end via the integration tests (real Gotenberg: the rendered PDF text carries the
  marker/`AM-<LAST6>-<DDMMYY>` + URL + `Page X of Y`; the preview HTML body carries the provenance line)
  and the frontend test (post-save number shown + fed into the preview). **Manual browser eyeball on
  `:8090` not performed** -- optional confirmation left to the reviewer (backend `:8090`, frontend
  `npm run dev`, compose infra up).

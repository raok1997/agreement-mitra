/**
 * Documents module: compiles an effective template definition + a data map to a self-contained HTML
 * document and renders it to a PDF. Rendering uses headless Chromium via a <b>Gotenberg</b> compose
 * service (not an in-process browser) so complex Indic scripts shape correctly -- a pure-Java PDF
 * library cannot -- with the Noto {@code @font-face} data-URIs embedded in the compiled HTML. The
 * app is a plain HTTP client; no browser binary ships in the app.
 *
 * <p>Module API: the root package exposes only the {@link
 * in.agreementmitra.documents.HtmlPdfRenderer} HTML-to-PDF seam (and {@link
 * in.agreementmitra.documents.DocumentRenderException}); the compiler-backed document projection is
 * exposed through the {@code documents.api} named interface ({@link
 * in.agreementmitra.documents.api.DocumentProjectionApi}). The Gotenberg client, the seam
 * implementation, the template compiler, and the projection service are package-private internals,
 * so other modules depend only on the public interfaces (Modulith-clean).
 *
 * <p><b>Execution block + eSign anchors:</b> a rendered document carries an execution / signature
 * block -- one signature zone per signatory with a stable, non-PII {@code esign:<role>} text anchor
 * the {@code signing} module maps to the provider's signature field. This module stays
 * eSign-agnostic: it emits an anchor token, never a provider field. The document reference + "page
 * X of Y" footer are render-layer furniture (not part of the compiled body HTML, so the effective-
 * template pin is unaffected). <b>Audit-trail annexure:</b> after signing, the eSign provider
 * appends its own audit page (masked Aadhaar, transaction id, OTP verification) to the signed PDF
 * -- this module never renders, stores, or logs that page; it only leaves the signed artifact's
 * layout free for the provider to append it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Documents")
package in.agreementmitra.documents;

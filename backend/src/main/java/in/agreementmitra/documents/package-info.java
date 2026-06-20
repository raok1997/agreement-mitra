/**
 * Documents module: renders an agreement template to PDF. Uses headless Chromium (Playwright) so
 * complex Indic scripts shape correctly — a pure-Java PDF library cannot. Bundle Noto fonts for the
 * vernacular feature.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Documents")
package in.agreementmitra.documents;

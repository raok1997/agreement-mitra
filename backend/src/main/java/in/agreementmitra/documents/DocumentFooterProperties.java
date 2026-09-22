package in.agreementmitra.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the per-page render footer and the compiled document's screen-only notices,
 * bound from {@code documents.footer.*}. Neither field is a secret: {@code platformUrl} is the
 * footer's centre provenance cell (e.g. {@code agreementmitra.com}), and {@code screenNotice} is a
 * "this is not legal advice" line rendered <b>on screen only</b>, beside the provenance line.
 * Internal to the {@code documents} module.
 *
 * <p>Both <b>code defaults are blank</b> and a blank value means "omit", so the module carries
 * <b>no brand literal and no legal copy</b> -- those live in {@code application.yml}, keeping
 * {@code documents} domain-agnostic. Both values are display text only: HTML-escaped at render
 * time, never turned into a link or fetched (the render stays offline, and the preview iframe is
 * fully sandboxed, so a link there would not navigate anyway).
 *
 * @param platformUrl the footer provenance URL; blank/null means "no centre cell"
 * @param screenNotice the screen-only advisory line under the document body; blank/null means "no
 *     notice". It is <b>never</b> printed: it is hidden in print media exactly as the provenance
 *     line is, so it cannot reach the executed instrument.
 */
@ConfigurationProperties(prefix = "documents.footer")
public record DocumentFooterProperties(String platformUrl, String screenNotice) {

  public DocumentFooterProperties {
    platformUrl = platformUrl == null ? "" : platformUrl.strip();
    screenNotice = screenNotice == null ? "" : screenNotice.strip();
  }
}

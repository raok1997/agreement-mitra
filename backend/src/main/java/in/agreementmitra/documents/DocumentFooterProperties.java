package in.agreementmitra.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the per-page render footer, bound from {@code documents.footer.*}. The only
 * field is a non-secret {@code platformUrl} shown as the footer's centre provenance cell (e.g.
 * {@code agreementmitra.com}). Internal to the {@code documents} module.
 *
 * <p>The <b>code default is blank</b> and a blank value means "omit the centre cell", so the module
 * carries <b>no brand literal</b> -- the brand value lives in {@code application.yml}, keeping
 * {@code documents} domain-agnostic. The value is display text only: it is HTML-escaped at render
 * time and is never turned into a link or fetched (the render stays offline).
 *
 * @param platformUrl the footer provenance URL; blank/null means "no centre cell"
 */
@ConfigurationProperties(prefix = "documents.footer")
public record DocumentFooterProperties(String platformUrl) {

  public DocumentFooterProperties {
    platformUrl = platformUrl == null ? "" : platformUrl.strip();
  }
}

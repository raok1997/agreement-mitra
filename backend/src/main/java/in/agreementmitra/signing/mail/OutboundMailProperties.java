package in.agreementmitra.signing.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the outbound email seam.
 *
 * <p><b>One adapter serves both environments</b> (design D7). Development uses the free Zoho Mail
 * mailbox; production uses Zoho ZeptoMail. Both speak SMTP, so the upgrade is host plus credentials
 * rather than a new integration:
 *
 * <ul>
 *   <li>development: host {@code smtp.zoho.*}, username = the mailbox address, password = its
 *       credential;
 *   <li>production: host {@code smtp.zeptomail.*}, username = the literal {@code emailapikey},
 *       password = the ZeptoMail send token.
 * </ul>
 *
 * <p>Ports 465 (implicit SSL) or 587 (STARTTLS) in both cases. <b>Match the Zoho data centre</b>
 * ({@code .in} vs {@code .com}) to the account, or authentication fails in a confusing way that
 * looks like a wrong password.
 *
 * <p><b>Defaults are fail-safe, not convenient.</b> {@code provider} defaults to {@code stub}, so a
 * misconfigured deployment sends nothing rather than sending to the wrong place, and the whole test
 * suite runs without a mailbox. Host, username and password have <b>empty</b> defaults - the
 * production host is never a default - and the credentials come from environment variables only.
 */
@ConfigurationProperties(prefix = "mail")
public record OutboundMailProperties(
    Provider provider, String from, String fromName, long maxAttachmentBytes, Smtp smtp) {

  /** Which {@link in.agreementmitra.signing.EmailSender} adapter is active. */
  public enum Provider {
    /** Captures messages in memory and sends nothing. The default. */
    STUB,
    /** The single SMTP adapter (Zoho Mail in development, ZeptoMail in production). */
    SMTP
  }

  /**
   * Attachment ceiling on the <b>raw</b> PDF, derived from the provider's <b>assembled-message</b>
   * limit (design D7a). ZeptoMail caps a message at 15 MB in total - headers, body, inline content
   * and attachments combined - and MIME base64 inflates binary content by roughly a third. 10 MiB
   * raw therefore assembles to roughly 13.4 MB plus headers, which leaves real headroom.
   *
   * <p>Measuring the raw file against 15 MB would be the bug this constant exists to prevent: the
   * message would be rejected by the provider AFTER we recorded it as sent, and the oversize
   * fallback (design D6) would never fire.
   *
   * <p>Chosen alongside the intake ceiling in {@code CertificateScanValidator} (8 MiB on the
   * uploaded certificate scan, which is the one input to the signed PDF with no natural size
   * bound). 10 MiB sits above it, so a maximal scan plus an ordinary rendered agreement normally
   * still attaches; the fallback covers the remainder rather than silently truncating.
   */
  public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

  public OutboundMailProperties {
    provider = provider == null ? Provider.STUB : provider;
    fromName = fromName == null || fromName.isBlank() ? "AgreementMitra" : fromName;
    maxAttachmentBytes = maxAttachmentBytes > 0 ? maxAttachmentBytes : DEFAULT_MAX_ATTACHMENT_BYTES;
    smtp = smtp == null ? new Smtp(null, 0, null, null, false, true, null) : smtp;
  }

  /**
   * SMTP transport settings. Every one of these is configuration; none is a provider concept that
   * escapes the seam.
   *
   * @param host the SMTP host; <b>empty by default</b> so a production host is never assumed
   * @param port 465 for implicit SSL, 587 for STARTTLS
   * @param username the mailbox address (Zoho Mail) or the literal {@code emailapikey} (ZeptoMail)
   * @param password the mailbox credential or the ZeptoMail send token; environment variable only
   * @param ssl implicit SSL on connect (port 465)
   * @param starttls upgrade the connection with STARTTLS (port 587)
   * @param timeout connect/read/write bound, so a hung provider surfaces as a clean transient
   *     failure instead of pinning a thread
   */
  public record Smtp(
      String host,
      int port,
      String username,
      String password,
      boolean ssl,
      boolean starttls,
      Duration timeout) {

    public Smtp {
      port = port > 0 ? port : 587;
      timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /** Never renders the password. */
    @Override
    public String toString() {
      return "Smtp{host="
          + host
          + ", port="
          + port
          + ", ssl="
          + ssl
          + ", starttls="
          + starttls
          + "}";
    }
  }

  /** Never renders the SMTP credential. */
  @Override
  public String toString() {
    return "OutboundMailProperties{provider=" + provider + ", smtp=" + smtp + "}";
  }
}

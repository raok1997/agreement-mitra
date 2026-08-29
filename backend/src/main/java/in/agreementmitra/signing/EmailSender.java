package in.agreementmitra.signing;

/**
 * Vendor-neutral outbound email seam, mirroring {@link EsignProvider} and {@link BlobStore}: one
 * adapter behind it, and no provider specifics past it.
 *
 * <p>Two implementations ship: a <b>stub</b> that captures messages in memory and sends nothing
 * (active by default, so tests and local runs never emit mail), and a single <b>SMTP</b> adapter
 * serving both environments - free Zoho Mail in development and Zoho ZeptoMail in production, whose
 * host, port, username and password are all configuration (design D7).
 *
 * <p><b>{@code send} returning normally means the provider accepted the message, not that it
 * arrived.</b> Plain SMTP has no bounce channel; ZeptoMail's bounce webhook closes that gap in
 * production and is deliberately out of scope here. Nothing in this codebase may present acceptance
 * as proof of delivery.
 */
public interface EmailSender {

  /**
   * Hand one message to the provider.
   *
   * @throws EmailDeliveryException if the provider refused or could not be reached; the exception
   *     classifies the failure as transient (retryable) or permanent (escalate)
   */
  void send(EmailMessage message);
}

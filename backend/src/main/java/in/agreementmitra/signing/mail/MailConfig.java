package in.agreementmitra.signing.mail;

import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Builds the SMTP transport for {@link SmtpEmailSender}, and only when {@code mail.provider=smtp}.
 *
 * <p>The sender is constructed here from {@code mail.smtp.*} rather than left to Boot's {@code
 * spring.mail.*} auto-configuration, for one reason: the production host must never be a default.
 * With no {@code mail.smtp.host} configured, this bean does not exist, the SMTP adapter does not
 * exist, and the stub stays active - so a half-configured deployment sends nothing instead of
 * sending somewhere unintended.
 *
 * <p>Credentials come from environment variables only and are never logged. Port 465 uses implicit
 * SSL; port 587 upgrades with STARTTLS. Both apply equally to Zoho Mail and to ZeptoMail - the
 * difference between the two environments is the host and the credential pair, nothing else.
 */
@Configuration
@EnableConfigurationProperties(OutboundMailProperties.class)
@ConditionalOnProperty(prefix = "mail", name = "provider", havingValue = "smtp")
class MailConfig {

  @Bean
  JavaMailSender javaMailSender(OutboundMailProperties properties) {
    OutboundMailProperties.Smtp smtp = properties.smtp();
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(smtp.host());
    sender.setPort(smtp.port());
    sender.setUsername(smtp.username());
    sender.setPassword(smtp.password());
    sender.setDefaultEncoding("UTF-8");

    long timeoutMillis = smtp.timeout().toMillis();
    Properties mailProperties = new Properties();
    mailProperties.put("mail.transport.protocol", "smtp");
    mailProperties.put("mail.smtp.auth", "true");
    // TLS is not optional on either provider. STARTTLS is REQUIRED (not merely enabled) so the
    // session fails rather than silently continuing in plaintext if the upgrade does not happen.
    mailProperties.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));
    mailProperties.put("mail.smtp.starttls.required", String.valueOf(smtp.starttls()));
    if (smtp.ssl()) {
      mailProperties.put("mail.smtp.ssl.enable", "true");
    }
    // Verify the server certificate against the configured host. Without this a hostile DNS answer
    // could terminate the session and read a document carrying both parties' identities.
    mailProperties.put("mail.smtp.ssl.checkserveridentity", "true");
    // Bound every phase, so a hung provider surfaces as a clean transient failure and the delivery
    // record retries, instead of a thread parking indefinitely mid-fulfilment.
    mailProperties.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
    mailProperties.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
    mailProperties.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
    sender.setJavaMailProperties(mailProperties);
    return sender;
  }
}

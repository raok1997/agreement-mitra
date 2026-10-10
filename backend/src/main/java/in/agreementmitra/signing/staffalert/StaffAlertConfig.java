package in.agreementmitra.signing.staffalert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the staff alert channel. The notifier is built unconditionally - with a blank URL, as every
 * test context and every boot before provisioning has - and reports itself unconfigured; "blank
 * means off" cannot be a {@code @ConditionalOnProperty}, which treats an empty value as present.
 */
@Configuration(proxyBeanMethods = false)
class StaffAlertConfig {

  @Bean
  StaffNotifier staffNotifier(StaffAlertProperties properties) {
    return new DiscordStaffNotifier(
        properties.discord().webhookUrl(),
        DiscordStaffNotifier.CONNECT_TIMEOUT,
        DiscordStaffNotifier.READ_TIMEOUT);
  }
}

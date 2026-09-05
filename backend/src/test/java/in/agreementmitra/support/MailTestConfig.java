package in.agreementmitra.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Swaps the shipped stub email seam for the {@link RecordingEmailSender} in the delivery tests, so
 * a test can inject a failure as well as inspect what was sent.
 *
 * <p>{@code @Primary} rather than a bean-definition override: the shipped stub bean still exists
 * and is still what every other test in the suite gets, which is the property that actually matters
 * - nothing anywhere here needs a mailbox, a credential, or a network connection.
 */
@TestConfiguration
public class MailTestConfig {

  @Bean
  @Primary
  public RecordingEmailSender recordingEmailSender() {
    return new RecordingEmailSender();
  }
}

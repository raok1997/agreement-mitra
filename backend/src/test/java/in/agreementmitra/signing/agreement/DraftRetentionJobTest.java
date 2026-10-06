package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Unit tests for {@link DraftRetentionJob}: the catch-all, and that the job is opt-in. */
class DraftRetentionJobTest {

  @RegisterExtension final LogCapture logs = LogCapture.of(DraftRetentionJob.class, Level.DEBUG);

  @Test
  void aFailingRunReturnsNormallyAndLogsTheClassNameOnly() {
    DraftRetention retention = mock(DraftRetention.class);
    when(retention.run(any())).thenThrow(new IllegalStateException("secret-detail"));

    assertThatCode(() -> new DraftRetentionJob(retention).run()).doesNotThrowAnyException();

    assertThat(logs.messages())
        .singleElement()
        .satisfies(m -> assertThat(m).contains("IllegalStateException").doesNotContain("secret"));
    assertThat(logs.throwableMessages()).isEmpty();
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withBean(DraftRetention.class, () -> mock(DraftRetention.class))
          .withUserConfiguration(DraftRetentionJob.class);

  @Test
  void theJobIsAbsentWhenThePropertyIsUnset() {
    runner.run(context -> assertThat(context).doesNotHaveBean(DraftRetentionJob.class));
  }

  @Test
  void theJobIsAbsentWhenDisabled() {
    runner
        .withPropertyValues("signing.draft-retention.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DraftRetentionJob.class));
  }

  @Test
  void theJobIsPresentWhenEnabled() {
    runner
        .withPropertyValues("signing.draft-retention.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(DraftRetentionJob.class));
  }
}

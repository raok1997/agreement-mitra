package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binding-time checks on the route-class limits (anonymous-surface-abuse-controls task 10.7). */
class AbuseLimitsPropertiesTest {

  private static Map<String, String> validConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("abuse.limits.enabled", "true");
    config.put("abuse.limits.maximum-size", "1000");
    for (String c :
        new String[] {
          "render",
          "live-preview",
          "anonymous-write",
          "auth",
          "capability-write",
          "capability-read",
          "bootstrap",
          "default"
        }) {
      config.put("abuse.limits.classes." + c + ".per-source", "30");
      config.put("abuse.limits.classes." + c + ".window", "PT1M");
      config.put("abuse.limits.classes." + c + ".lockout", "PT5M");
    }
    config.put("abuse.limits.classes.capability-read.per-resource", "60");
    return config;
  }

  private static AbuseLimitsProperties bind(Map<String, String> config) {
    return new Binder(new MapConfigurationPropertySource(config))
        .bind("abuse.limits", AbuseLimitsProperties.class)
        .get();
  }

  @Test
  void aCompleteConfigurationBindsByKebabCaseClassName() {
    AbuseLimitsProperties properties = bind(validConfig());
    assertThat(properties.classes().get(RouteClass.CAPABILITY_READ).perResource()).isEqualTo(60);
    assertThat(properties.classes().get(RouteClass.ANONYMOUS_WRITE).perResource()).isNull();
    assertThat(properties.retention()).isEqualTo(Duration.ofMinutes(6));
  }

  @Test
  void bindingRejectsAClassWhosePerResourceLimitIsNotAboveItsPerSourceLimit() {
    // Equal is not enough: one source could then fill the agreement's bucket by itself.
    Map<String, String> config = validConfig();
    config.put("abuse.limits.classes.capability-read.per-resource", "30");
    assertThatThrownBy(() -> bind(config))
        .isInstanceOf(BindException.class)
        .rootCause()
        .hasMessageContaining("per-resource limit must be higher than its per-source limit");
  }

  @Test
  void bindingRejectsAMissingClassSoNoValueIsCompiledIn() {
    Map<String, String> config = validConfig();
    config.keySet().removeIf(key -> key.startsWith("abuse.limits.classes.render."));
    assertThatThrownBy(() -> bind(config))
        .isInstanceOf(BindException.class)
        .rootCause()
        .hasMessageContaining("RENDER");
  }
}

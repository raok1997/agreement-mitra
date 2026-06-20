package in.agreementmitra.signing;

import in.agreementmitra.support.HarnessTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the Spring Modulith slice-test <em>mechanism</em> wires up: the {@code signing} module
 * bootstraps in isolation against the harness, without loading unrelated modules. This is a boot
 * smoke only — modules are still stubs, so there is no behavioral assertion. The first real module
 * slice test ships with the feature that adds signing behavior.
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon.
 */
@ApplicationModuleTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SigningModuleSliceTest {

  @Test
  void signingModuleBootsInIsolation() {
    // Context start = the slice mechanism is wired. No behavioral assertion (stubs only).
  }
}

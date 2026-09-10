package in.agreementmitra.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The Gotenberg renderer for the tests that actually produce a PDF. Import it alongside {@link
 * HarnessTestConfig}; leave it out of every other test, which has no reason to pay for a Chromium.
 *
 * <p>One container and one image build per JVM. Five test classes each declared their own
 * {@code @Container} over a bare {@code new ImageFromDockerfile()}, and that constructor generates
 * a <em>random</em> image name — so a single suite run built the same {@code apt-get install
 * fonts-noto-core} Dockerfile five times under five throwaway tags (observed 2026-09-10 as {@code
 * localhost/testcontainers/wbkjob4pmkdklmff} and four siblings) and started five Chromiums. The
 * fixed tag below means Docker reuses the built image across runs too, not just within one.
 *
 * <p>Sharing is safe here in a way it would not be for a stateful service: Gotenberg is a
 * request-scoped renderer that holds nothing between calls.
 *
 * <p>The container is never stopped explicitly — Ryuk reaps it when the test JVM exits, as with
 * {@link HarnessTestConfig}. {@code gotenberg.url} reaches the application through a {@link
 * DynamicPropertyRegistrar} bean, so importing this config is the only thing a test needs to do;
 * the per-class {@code @DynamicPropertySource} that used to publish that URL is gone, which also
 * stops those five classes fragmenting the Spring test context cache.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GotenbergTestConfig {

  private static final int GOTENBERG_PORT = 3000;

  private static final GenericContainer<?> GOTENBERG =
      new GenericContainer<>(
              // Fixed tag + deleteOnExit=false: build once, reuse across runs. The default
              // constructor picks a random tag and deletes on exit, so every run rebuilds.
              new ImageFromDockerfile("agreementmitra/gotenberg-test:latest", false)
                  .withFileFromPath(".", Path.of("..", "docker", "gotenberg")))
          .withExposedPorts(GOTENBERG_PORT)
          // Deny Chromium's outbound network -- the SSRF/exfil guard (matches docker-compose).
          .withEnv("CHROMIUM_DENY_PUBLIC_IPS", "true")
          .withEnv("CHROMIUM_DENY_PRIVATE_IPS", "true");

  static {
    GOTENBERG.start();
  }

  @Bean
  DynamicPropertyRegistrar gotenbergPropertiesRegistrar() {
    return registry ->
        registry.add(
            "gotenberg.url",
            () -> "http://" + GOTENBERG.getHost() + ":" + GOTENBERG.getMappedPort(GOTENBERG_PORT));
  }
}

package in.agreementmitra.support;

import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

import io.minio.MinioClient;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;

/**
 * Shared integration-test infra, imported (not inherited) so it wires uniformly across
 * {@code @SpringBootTest} and {@code @ApplicationModuleTest}. Containers are Spring-managed beans,
 * auto-started by Boot's Testcontainers support and reused via the test context cache. Postgres is
 * wired into the datasource via {@link ServiceConnection} (no hand-rolled props); MinIO has no
 * {@code @ServiceConnection} starter, so its endpoint/credentials reach {@code storage.*} through a
 * {@link DynamicPropertyRegistrar} bean. Credentials are container defaults and ports are
 * container-mapped — no real secrets, no literal keys.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HarnessTestConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:16-alpine")
        .waitingFor(
            new WaitAllStrategy()
                // MUST come before withStrategy: WaitAllStrategy's default budget is 30s, HALF the
                // 60s a bare Postgres wait gets, and in the default WITH_OUTER_TIMEOUT mode
                // withStrategy() stamps the CURRENT outer timeout onto each child as it is added.
                // So wrapping the stock wait silently halved it, and setting this afterwards would
                // leave the children on 30s. That shortfall is why a loaded run fails on
                // postgresContainer and never on MinIO, whose stock HTTP wait keeps its own 60s:
                // a full suite starts a Postgres + MinIO pair per distinct Spring context (~19
                // here), and under that contention 30s is not enough for the container to come up
                // AND the host port forwarder to publish. The symptom is a startup TimeoutException
                // that reshuffles between runs and always passes on an isolated re-run.
                .withStartupTimeout(Duration.ofMinutes(3))
                // Postgres' own default: the service is up INSIDE the container. Kept, because the
                // host-port check below is necessary but not sufficient (see hostPortAccepts).
                .withStrategy(
                    Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(hostPortAccepts(POSTGRESQL_PORT)));
  }

  @Bean
  MinIOContainer minioContainer() {
    // No override: MinIOContainer's default is Wait.forHttp("/minio/health/live"), and an HTTP wait
    // already dials the mapped port from the host — so it covers both readiness AND the forwarder.
    return new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z");
  }

  /**
   * Waits until the container's mapped port actually accepts a TCP connection <em>from the
   * host</em> — not merely until the service reports ready in its own logs.
   *
   * <p>Why this is needed: Postgres' stock wait strategy is log-based, which only proves the
   * service is up <em>inside</em> the container. On VM-backed Docker runtimes whose host port
   * forwarder is asynchronous — Rancher Desktop's experimental {@code sshPortForwarder}, measured
   * here lagging readiness by 0.3–1.8s — the log line lands while the host-side port is still
   * unbound. Testcontainers then returns, Flyway dials {@code localhost:<mapped>}, and gets {@code
   * java.net.ConnectException: Connection refused}. This closes that window by polling the
   * forwarded port the same way the application will reach it.
   *
   * <p>Deliberately paired with (never a replacement for) the log check: the same forwarder accepts
   * TCP <em>before</em> the service behind it is ready, so on its own this would return too early
   * and trade one flake for another. Harmless on Docker Desktop/Linux, where forwarding is
   * synchronous and the first poll passes.
   */
  private static WaitStrategy hostPortAccepts(int containerPort) {
    return new AbstractWaitStrategy() {
      @Override
      protected void waitUntilReady() {
        Unreliables.retryUntilSuccess(
            (int) startupTimeout.getSeconds(),
            TimeUnit.SECONDS,
            () -> {
              try (Socket socket = new Socket()) {
                socket.connect(
                    new InetSocketAddress(
                        waitStrategyTarget.getHost(),
                        waitStrategyTarget.getMappedPort(containerPort)),
                    1_000);
              }
              return true;
            });
      }
    };
  }

  @Bean
  DynamicPropertyRegistrar storagePropertiesRegistrar(MinIOContainer minio) {
    return registry -> {
      registry.add("storage.endpoint", minio::getS3URL);
      registry.add("storage.access-key", minio::getUserName);
      registry.add("storage.secret-key", minio::getPassword);
      registry.add("storage.bucket", () -> "harness-test");
    };
  }

  @Bean
  MinioClient minioClient(MinIOContainer minio) {
    return MinioClient.builder()
        .endpoint(minio.getS3URL())
        .credentials(minio.getUserName(), minio.getPassword())
        .build();
  }
}

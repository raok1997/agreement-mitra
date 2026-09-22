package in.agreementmitra.support;

import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

import io.minio.MinioClient;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
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
 * {@code @SpringBootTest} and {@code @ApplicationModuleTest}.
 *
 * <p><strong>The containers are JVM-wide singletons, deliberately NOT Spring beans.</strong> They
 * were beans once, and Boot's Testcontainers support then bound their lifecycle to context
 * refresh/close — so every distinct Spring context in the suite started its own Postgres AND its
 * own MinIO, and the test context cache never evicts, so none of them were ever released. Measured
 * on 2026-09-10: one {@code ./run-tests.sh check} created <em>24 Postgres + 25 MinIO</em>
 * containers, held 49 alive at once, and spent 7m56s of an 8m04s build inside {@code :test} — ~7s
 * of pure container+Flyway churn per context, degrading to 44s at the tail as the Docker VM
 * saturated. It also FAILED: the 25th MinIO timed out in its HTTP wait under that load and took 12
 * tests in {@code ZoopSigningIntegrationTest} with it. One pair per JVM removes both the time and
 * that whole class of flake.
 *
 * <p><strong>Isolation is unchanged</strong>: what is shared is the container, not the data. Each
 * Spring context gets its own logical database ({@code test_<n>}) on the one Postgres server and
 * its own bucket ({@code harness-test-<n>}) on the one MinIO, so Flyway migrates a virgin schema
 * per context exactly as it did when every context had a container to itself. Tests may keep
 * asserting on global state ({@code findPublished(null, null, "%")} and friends) — sharing the
 * database instead was tried first and broke precisely those tests, which were right to assert what
 * they assert.
 *
 * <p>The containers are never stopped explicitly. Ryuk reaps them when the test JVM exits — the
 * same guarantee the rest of the harness relies on, and the reason {@code TESTCONTAINERS_RYUK_
 * DISABLED} must stay off (see CLAUDE.md). {@code @ServiceConnection} is unavailable here because
 * it only works on a container <em>bean</em>, so Postgres is wired through the {@link
 * JdbcConnectionDetails} bean that annotation would itself have produced, and MinIO — which has no
 * connection-details starter — through a {@link DynamicPropertyRegistrar}. Credentials are
 * container defaults and ports are container-mapped — no real secrets, no literal keys.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HarnessTestConfig {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          // One Postgres now serves EVERY cached Spring context at once, and the test context cache
          // never closes a context, so every context's Hikari pool stays open for the whole run.
          // Postgres' stock max_connections=100 is not enough for that: with ~24 contexts the suite
          // died on "FATAL: sorry, too many clients already" during Flyway. Raised here, and the
          // pool is capped per context in application-test.yml — both ends, so neither alone is
          // load-bearing.
          .withCommand("postgres", "-c", "max_connections=500")
          .waitingFor(
              new WaitAllStrategy()
                  // MUST come before withStrategy: WaitAllStrategy's default budget is 30s, HALF
                  // the 60s a bare Postgres wait gets, and in the default WITH_OUTER_TIMEOUT mode
                  // withStrategy() stamps the CURRENT outer timeout onto each child as it is added.
                  // So wrapping the stock wait silently halved it, and setting this afterwards
                  // would leave the children on 30s. Kept generous even now that only one container
                  // starts per JVM: the box may still be loaded by other work.
                  .withStartupTimeout(Duration.ofMinutes(3))
                  // Postgres' own default: the service is up INSIDE the container. Kept, because
                  // the host-port check below is necessary but not sufficient (see hostPortAccepts)
                  .withStrategy(
                      Wait.forLogMessage(
                          ".*database system is ready to accept connections.*\\s", 2))
                  .withStrategy(hostPortAccepts(POSTGRESQL_PORT)));

  // No wait override: MinIOContainer's default is Wait.forHttp("/minio/health/live"), and an HTTP
  // wait already dials the mapped port from the host — so it covers both readiness AND the
  // forwarder.
  private static final MinIOContainer MINIO =
      new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z");

  static {
    // Started once, on first class load — which only happens inside a test class that Docker is
    // actually available for, because every such class carries
    // @Testcontainers(disabledWithoutDocker = true) and is skipped before its context is built.
    POSTGRES.start();
    MINIO.start();
  }

  /**
   * One ordinal per Spring context. Spring instantiates this {@code @TestConfiguration} once per
   * context, so the field below hands each context a private database and a private bucket on the
   * shared servers — the isolation the suite was written against, without the container cost.
   */
  private static final AtomicInteger CONTEXTS = new AtomicInteger();

  private final int contextOrdinal = CONTEXTS.incrementAndGet();

  /**
   * Points the datasource at the container. This is the same seam {@code @ServiceConnection}
   * produces — a {@link JdbcConnectionDetails} bean, which {@code DataSourceAutoConfiguration}
   * prefers over any {@code spring.datasource.*} property — but taken directly, because
   * {@code @ServiceConnection} only works on a container <em>bean</em> and that is exactly the
   * per-context-container problem this class exists to avoid.
   *
   * <p><strong>Do not "simplify" this into a {@link DynamicPropertyRegistrar} that sets {@code
   * spring.datasource.url}.</strong> That was tried on 2026-09-11 and it silently lost to {@code
   * application.yml:18}, whose default is {@code jdbc:postgresql://localhost:5432/agreementmitra} —
   * so the whole suite connected to the developer's local compose database instead of the
   * container, wrote test fixtures into it, and only surfaced as a bizarre assertion failure in
   * {@code AgreementOwnershipIntegrationTest} where rows from earlier runs were still present. A
   * fallback URL that always resolves means a mis-wired datasource fails silently rather than
   * loudly; {@code JdbcConnectionDetails} takes precedence and leaves no such gap.
   */
  @Bean
  JdbcConnectionDetails jdbcConnectionDetails() {
    String database = "test_" + contextOrdinal;
    createDatabase(database);
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(POSTGRESQL_PORT)
            + "/"
            + database;
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return url;
      }

      @Override
      public String getUsername() {
        return POSTGRES.getUsername();
      }

      @Override
      public String getPassword() {
        return POSTGRES.getPassword();
      }
    };
  }

  /**
   * Creates this context's database on the shared server. {@code CREATE DATABASE} cannot be
   * parameterised, so the name is built solely from a private counter — nothing external reaches
   * this string.
   */
  private static void createDatabase(String database) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create test database " + database, e);
    }
  }

  /**
   * Fails the context if the datasource is not the throwaway container.
   *
   * <p>This exists because the failure it catches is silent and destructive: {@code
   * application.yml:18} defaults to {@code jdbc:postgresql://localhost:5432/agreementmitra}, which
   * on any developer machine running {@code docker compose up} is a REAL database that accepts the
   * connection and happily takes writes. A harness that mis-wires the datasource therefore does not
   * crash — it quietly fills the dev database with test fixtures, which is precisely what happened
   * on 2026-09-11. Ordered before Flyway so a mis-wire is caught before any migration or write.
   */
  @Bean
  static BeanFactoryPostProcessor datasourceIsTheContainerGuard() {
    return beanFactory ->
        beanFactory.addBeanPostProcessor(
            new BeanPostProcessor() {
              @Override
              public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                  String url;
                  try (Connection connection = dataSource.getConnection()) {
                    url = connection.getMetaData().getURL();
                  } catch (SQLException e) {
                    throw new IllegalStateException("Test datasource is unreachable", e);
                  }
                  // Host+port, not the whole URL: each context has its own database ON that
                  // server, so the database name legitimately differs. The server is the invariant
                  // that matters — never localhost:5432.
                  String server =
                      POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(POSTGRESQL_PORT) + "/";
                  if (!url.contains(server)) {
                    throw new IllegalStateException(
                        "Test datasource points at "
                            + url
                            + " instead of the Testcontainers Postgres at "
                            + server
                            + ". Refusing to run: this would write test fixtures into a real"
                            + " database. See HarnessTestConfig#jdbcConnectionDetails.");
                  }
                }
                return bean;
              }
            });
  }

  @Bean
  DynamicPropertyRegistrar storagePropertiesRegistrar() {
    return registry -> {
      registry.add("storage.endpoint", MINIO::getS3URL);
      registry.add("storage.access-key", MINIO::getUserName);
      registry.add("storage.secret-key", MINIO::getPassword);
      registry.add("storage.bucket", () -> "harness-test-" + contextOrdinal);
    };
  }

  @Bean
  MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(MINIO.getS3URL())
        .credentials(MINIO.getUserName(), MINIO.getPassword())
        .build();
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
}

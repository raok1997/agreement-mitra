package in.agreementmitra.support;

import io.minio.MinioClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

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
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }

  @Bean
  MinIOContainer minioContainer() {
    return new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z");
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

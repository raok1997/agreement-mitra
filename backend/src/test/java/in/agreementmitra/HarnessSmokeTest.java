package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the integration-test harness boots end to end: the full Spring context starts against the
 * Testcontainers PostgreSQL (via {@code @ServiceConnection}) and the MinIO container is reachable
 * through a bucket create/exists round-trip. Asserts nothing about the (still-stubbed) signing API
 * — that is deferred to the first signing-feature CR.
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class HarnessSmokeTest {

  @Autowired private MinioClient minioClient;

  @Test
  void contextBootsAgainstTestcontainersPostgres() {
    // Reaching this point means the context loaded with the @ServiceConnection datasource.
  }

  @Test
  void minioContainerIsReachableViaBucketRoundTrip() throws Exception {
    String bucket = "harness-smoke";

    if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    assertThat(minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        .isTrue();
  }
}

package in.agreementmitra.signing.storage;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the object-storage adapter: enables {@link StorageProperties} and builds the {@link
 * MinioClient} the {@link MinioBlobStore} uses, from the {@code storage.*} endpoint + credentials.
 * Internal to the module. The bean is {@link ConditionalOnMissingBean} so the integration-test
 * harness (which points a client at the Testcontainers MinIO) overrides it without conflict.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

  @Bean
  @ConditionalOnMissingBean
  MinioClient minioClient(StorageProperties properties) {
    return MinioClient.builder()
        .endpoint(properties.endpoint())
        .credentials(properties.accessKey(), properties.secretKey())
        .build();
  }
}

package in.agreementmitra.signing.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage configuration, bound from {@code storage.*}. Endpoint, bucket, and credentials
 * come from env vars (sandbox MinIO locally); nothing is committed. Internal to the signing module.
 */
@ConfigurationProperties(prefix = "storage")
record StorageProperties(String endpoint, String bucket, String accessKey, String secretKey) {}

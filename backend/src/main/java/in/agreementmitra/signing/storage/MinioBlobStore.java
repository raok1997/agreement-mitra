package in.agreementmitra.signing.storage;

import in.agreementmitra.signing.BlobStore;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MinIO (S3-API) adapter for {@link BlobStore}. Internal to the signing module — nothing outside
 * the module references it; callers depend on {@link BlobStore}. Uses path-style addressing (the
 * MinIO default) and ensures the (private) bucket exists on first use, so neither local boot nor
 * the Testcontainers test depends on out-of-band bucket bootstrap. Never logs object bytes.
 */
@Component
class MinioBlobStore implements BlobStore {

  private static final Logger log = LoggerFactory.getLogger(MinioBlobStore.class);

  private final MinioClient client;
  private final String bucket;

  MinioBlobStore(MinioClient client, StorageProperties properties) {
    this.client = client;
    this.bucket = properties.bucket();
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    ensureBucket();
    try (InputStream in = new ByteArrayInputStream(bytes)) {
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(in, bytes.length, -1)
              .contentType(contentType)
              .build());
      log.debug("Stored object {} ({} bytes)", key, bytes.length);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to store object " + key, e);
    }
  }

  @Override
  public byte[] get(String key) {
    try (InputStream in =
        client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      return in.readAllBytes();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read object " + key, e);
    }
  }

  private void ensureBucket() {
    try {
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ensure bucket " + bucket, e);
    }
  }
}

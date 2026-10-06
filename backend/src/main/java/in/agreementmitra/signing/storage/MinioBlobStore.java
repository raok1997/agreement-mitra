package in.agreementmitra.signing.storage;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
      log.debug("Stored object {} ({} bytes)", AgreementIds.redactIn(key), bytes.length);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to store object " + AgreementIds.redactIn(key), e);
    }
  }

  @Override
  public byte[] get(String key) {
    try (InputStream in =
        client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      return in.readAllBytes();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read object " + AgreementIds.redactIn(key), e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to delete object " + AgreementIds.redactIn(key), e);
    }
  }

  /**
   * The cause is dropped, not chained: a storage-client exception can carry an object name, and an
   * object name here holds a full agreement id.
   */
  @Override
  public List<StoredObject> list(String prefix) {
    try {
      List<StoredObject> objects = new ArrayList<>();
      for (Result<Item> result :
          client.listObjects(
              ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
        Item item = result.get();
        if (!item.isDir()) {
          objects.add(new StoredObject(item.objectName(), item.lastModified().toInstant()));
        }
      }
      return objects;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to list objects under " + prefix + " (" + e.getClass().getSimpleName() + ")");
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

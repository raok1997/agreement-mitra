package in.agreementmitra.signing.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.AgreementIds;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A failed read or write names the object key with its agreement id redacted
 * (agreement-id-debug-logging 3.5).
 */
class MinioBlobStoreTest {

  private static final UUID ID = UUID.randomUUID();
  private static final String KEY = "drafts/" + ID + ".pdf";
  private static final String REDACTED_KEY = "drafts/" + AgreementIds.redact(ID) + ".pdf";

  private final MinioClient client = mock(MinioClient.class);
  private MinioBlobStore store;

  @BeforeEach
  void setUp() throws Exception {
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    store = new MinioBlobStore(client, new StorageProperties("http://minio", "bucket", "a", "s"));
  }

  @Test
  void aFailedWriteRedactsTheKey() throws Exception {
    when(client.putObject(any(PutObjectArgs.class))).thenThrow(new IOException("boom"));

    assertThatThrownBy(() -> store.put(KEY, new byte[] {1}, "application/pdf"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failed to store object " + REDACTED_KEY);
  }

  @Test
  void aFailedReadRedactsTheKey() throws Exception {
    when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("boom"));

    assertThatThrownBy(() -> store.get(KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failed to read object " + REDACTED_KEY);
  }
}

package in.agreementmitra.signing;

/**
 * Vendor-neutral object storage for signed artifacts (MinIO locally / S3-compatible in prod).
 * Mirrors the {@link EsignProvider} seam: all storage specifics (endpoint, bucket, credentials, S3
 * client) live behind a provider-specific adapter. PDF/audit <em>bytes</em> go here only — never to
 * Postgres, never to logs. Keys are deterministic and derived from the internal signing-request id.
 */
public interface BlobStore {

  /** Store {@code bytes} under {@code key} with the given content type, overwriting if present. */
  void put(String key, byte[] bytes, String contentType);

  /** Read the bytes stored under {@code key}. */
  byte[] get(String key);
}

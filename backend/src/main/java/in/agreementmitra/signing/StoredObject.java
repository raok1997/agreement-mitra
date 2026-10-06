package in.agreementmitra.signing;

import java.time.Instant;

/** One object a {@link BlobStore#list} found: its exact key and when it was last written. */
public record StoredObject(String key, Instant lastModified) {}

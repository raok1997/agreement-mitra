package in.agreementmitra.documents;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Gotenberg render service, bound from {@code gotenberg.*}. {@code url} is a
 * non-secret base URL (env {@code GOTENBERG_URL}, default the local docker-compose service), like
 * the S3 endpoint. Internal to the {@code documents} module.
 *
 * @param url Gotenberg base URL (host root; the client appends the render route)
 * @param maxConcurrentRenders in-app cap on concurrent renders (Chromium's memory lives in
 *     Gotenberg, not the JVM; this bounds how many renders the app fires at once)
 * @param requestTimeout per-render client read timeout; a stuck render surfaces as a clean failure
 * @param admissionWait the longest a render waits for a slot before it is refused
 * @param maxWaiters how many renders may wait for a slot at once; beyond it a render is refused at
 *     once. Each waiter holds a DB connection, so slots plus waiters bound what a flood can pin.
 * @param reservedRenders slots kept for {@link RenderPriority#FULFILMENT} renders; must be fewer
 *     than {@code maxConcurrentRenders}
 */
@ConfigurationProperties(prefix = "gotenberg")
record GotenbergProperties(
    String url,
    int maxConcurrentRenders,
    Duration requestTimeout,
    Duration admissionWait,
    int maxWaiters,
    int reservedRenders) {

  GotenbergProperties {
    if (maxConcurrentRenders <= 0) {
      maxConcurrentRenders = 4;
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = Duration.ofSeconds(30);
    }
    if (admissionWait == null || admissionWait.isNegative()) {
      admissionWait = Duration.ofSeconds(2);
    }
    if (maxWaiters < 0) {
      throw new IllegalArgumentException("gotenberg.max-waiters must not be negative");
    }
    if (reservedRenders < 0 || reservedRenders >= maxConcurrentRenders) {
      throw new IllegalArgumentException(
          "gotenberg.reserved-renders must be at least 0 and less than max-concurrent-renders,"
              + " or no render but a fulfilment one could ever run");
    }
  }

  /** The slots a {@link RenderPriority#STANDARD} render may take. */
  int generalRenders() {
    return maxConcurrentRenders - reservedRenders;
  }
}

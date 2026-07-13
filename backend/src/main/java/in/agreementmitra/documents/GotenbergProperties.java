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
 */
@ConfigurationProperties(prefix = "gotenberg")
record GotenbergProperties(String url, int maxConcurrentRenders, Duration requestTimeout) {

  GotenbergProperties {
    if (maxConcurrentRenders <= 0) {
      maxConcurrentRenders = 4;
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = Duration.ofSeconds(30);
    }
  }
}

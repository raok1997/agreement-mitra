package in.agreementmitra.identity.oauth;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * The one HTTP client configuration for calls to Google: the token POST and the JWKS fetch. Both
 * run inside the callback's transaction, after the login-state row is consumed and locked, so an
 * unbounded call would hold that lock indefinitely and outlive the login-binding cookie. A callback
 * makes at most {@value #MAX_CALLS_PER_CALLBACK} such calls (token POST, JWKS fetch, and a JWKS
 * refresh when Google rotates keys), and the timeouts keep all of them well under the cookie's 60 s
 * margin (login-browser-binding D1). One shared client, so both callers reuse its connections.
 */
final class GoogleHttp {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(7);
  static final int MAX_CALLS_PER_CALLBACK = 3;

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  private GoogleHttp() {}

  static ClientHttpRequestFactory requestFactory() {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(CLIENT);
    factory.setReadTimeout(READ_TIMEOUT);
    return factory;
  }

  static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(connectTimeout).build());
    factory.setReadTimeout(readTimeout);
    return factory;
  }
}

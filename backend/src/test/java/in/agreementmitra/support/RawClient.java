package in.agreementmitra.support;

import java.net.http.HttpClient;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * A random-port {@link RestTemplate} for the CSRF/cookie ENFORCEMENT tests: built WITHOUT the
 * {@link CsrfTestInterceptor} customizer, so whatever cookies and headers the test sets are exactly
 * what is sent. Errors do not throw (status assertions read the response).
 *
 * <p>Pinned to {@link JdkClientHttpRequestFactory}: the default {@code
 * SimpleClientHttpRequestFactory} streams bodies, and {@code HttpURLConnection} throws {@code
 * HttpRetryException} on a 401 to a streamed POST -- several assertions here expect exactly that
 * 401. The JDK client keeps no cookie jar and follows no redirects by default.
 */
public final class RawClient {

  private RawClient() {}

  public static RestTemplate on(int port) {
    RestTemplate template =
        new RestTemplate(
            new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
    template.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
    template.setErrorHandler(
        new DefaultResponseErrorHandler() {
          @Override
          public boolean hasError(ClientHttpResponse response) {
            return false;
          }
        });
    return template;
  }
}

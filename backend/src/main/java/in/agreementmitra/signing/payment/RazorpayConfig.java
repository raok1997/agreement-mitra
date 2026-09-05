package in.agreementmitra.signing.payment;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the Razorpay adapter: enables {@link RazorpayProperties} and builds the {@link RestClient}
 * the adapter uses. Internal to the signing module.
 *
 * <p>Auth is HTTP <b>Basic</b> - key id as the username, key secret as the password - which is what
 * Razorpay's Orders API expects. The header is assembled once here so the secret exists in exactly
 * one place in the wiring and no call site can accidentally put it somewhere else (a query
 * parameter, a log line, a response body).
 *
 * <p>Pinned to HTTP/1.1 for the same reason the ZOOP and Leegality clients are: the JDK client
 * otherwise attempts an h2c upgrade that the provider (and the WireMock stub) reset.
 *
 * <p>The bean is built unconditionally so the payment endpoints always exist; with no credentials
 * configured the client is simply never used ({@link RazorpayClient} refuses before calling out).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RazorpayProperties.class)
class RazorpayConfig {

  @Bean
  RestClient razorpayRestClient(RestClient.Builder builder, RazorpayProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return builder
        .baseUrl(properties.baseUrl())
        .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(properties))
        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
        .build();
  }

  /**
   * {@code Basic base64(keyId:keySecret)}. Built even when unset (empty strings) so the bean shape
   * never varies; the adapter refuses to call out at all in that case.
   */
  private static String basicAuth(RazorpayProperties properties) {
    String keyId = properties.keyId() == null ? "" : properties.keyId();
    String keySecret = properties.keySecret() == null ? "" : properties.keySecret();
    String encoded =
        Base64.getEncoder()
            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    return "Basic " + encoded;
  }
}

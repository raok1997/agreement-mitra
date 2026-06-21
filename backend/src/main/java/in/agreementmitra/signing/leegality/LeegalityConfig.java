package in.agreementmitra.signing.leegality;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the Leegality adapter: enables {@link LeegalityProperties} and builds the {@link
 * RestClient} the adapter uses. The client is rooted at the host base URL and carries the {@code
 * X-Auth-Token} header on every call; the adapter appends the per-endpoint version path. Internal
 * to the module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LeegalityProperties.class)
class LeegalityConfig {

  /**
   * Dedicated client for Leegality. Auth is a static {@code X-Auth-Token} header (NOT {@code
   * Authorization: Bearer}). Pinned to HTTP/1.1: the JDK client otherwise tries an h2c upgrade,
   * which the provider (and the WireMock stub) reset (RST_STREAM); HTTP/1.1 is what the Leegality
   * REST API speaks anyway.
   */
  @Bean
  RestClient leegalityRestClient(RestClient.Builder builder, LeegalityProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return builder
        .baseUrl(properties.baseUrl())
        .defaultHeader("X-Auth-Token", properties.authToken())
        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
        .build();
  }
}

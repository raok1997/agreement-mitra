package in.agreementmitra.signing.zoop;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the ZOOP eSign v5 adapter: enables {@link ZoopProperties} and builds the {@link RestClient}
 * the adapter uses. Active only when {@code esign.provider=zoop}, so exactly one {@code
 * EsignProvider} bean exists per configuration and switching vendors is a config flip (design D7).
 * Internal to the module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ZoopProperties.class)
@ConditionalOnProperty(name = "esign.provider", havingValue = "zoop")
class ZoopConfig {

  /**
   * Dedicated client for ZOOP. Auth is two static headers from their dashboard, {@code app-id} and
   * {@code api-key} - NOT {@code Authorization: Bearer}. Pinned to HTTP/1.1 for the same reason the
   * Leegality client is: the JDK client otherwise attempts an h2c upgrade that the provider (and
   * the WireMock stub) reset.
   */
  @Bean
  RestClient zoopRestClient(RestClient.Builder builder, ZoopProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return builder
        .baseUrl(properties.baseUrl())
        .defaultHeader("app-id", properties.appId() == null ? "" : properties.appId())
        .defaultHeader("api-key", properties.apiKey() == null ? "" : properties.apiKey())
        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
        .build();
  }
}

package in.agreementmitra.documents;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@code documents} render pipeline: enables {@link GotenbergProperties} and builds the
 * {@link RestClient} the {@code GotenbergClient} uses. Internal to the module -- nothing here is
 * part of the module's public API (only {@link HtmlPdfRenderer} is).
 *
 * <p>The client is rooted at the Gotenberg base URL with a per-render read timeout (a stuck render
 * fails cleanly rather than hanging a request thread). Mirrors the Leegality/Storage config style.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GotenbergProperties.class, DocumentFooterProperties.class})
class DocumentsConfig {

  @Bean
  RestClient gotenbergRestClient(RestClient.Builder builder, GotenbergProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.requestTimeout());
    return builder.baseUrl(properties.url()).requestFactory(factory).build();
  }
}

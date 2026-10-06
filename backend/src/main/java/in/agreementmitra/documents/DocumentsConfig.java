package in.agreementmitra.documents;

import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
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

  /**
   * Startup check (anonymous-surface-abuse-controls D6): every admitted render and every waiter can
   * hold a database connection -- the render paths run inside a transaction -- so render slots plus
   * the waiting room must leave connections free, or a render flood could still stall every
   * DB-backed endpoint. Skipped when the pool size cannot be read.
   */
  @Bean
  InitializingBean renderConnectionHeadroomCheck(
      GotenbergProperties properties, ObjectProvider<DataSource> dataSource) {
    return () -> {
      if (dataSource.getIfAvailable() instanceof HikariDataSource hikari) {
        checkConnectionHeadroom(properties, hikari.getMaximumPoolSize());
      }
    };
  }

  static void checkConnectionHeadroom(GotenbergProperties properties, int poolSize) {
    int pinnable = properties.maxConcurrentRenders() + properties.maxWaiters();
    if (pinnable >= poolSize) {
      throw new IllegalStateException(
          "gotenberg.max-concurrent-renders ("
              + properties.maxConcurrentRenders()
              + ") plus gotenberg.max-waiters ("
              + properties.maxWaiters()
              + ") must be fewer than the database pool size ("
              + poolSize
              + "), or a render flood can hold every connection");
    }
  }
}

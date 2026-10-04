package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Shipped logging and datasource defaults (agreement-id-debug-logging 5.2, 5.3a). */
class LoggingLevelDefaultsTest {

  private static final String LEVEL = "logging.level.in.agreementmitra";

  @Test
  void theApplicationLoggerDefaultsToInfo() throws IOException {
    assertThat(property("application.yml", LEVEL)).isEqualTo("INFO");
  }

  @Test
  void theLocalProfileKeepsDebug() throws IOException {
    assertThat(property("application-local.yml", LEVEL)).isEqualTo("DEBUG");
  }

  @Test
  void theDefaultDatasourceUrlSuppressesServerErrorDetail() throws IOException {
    assertThat(property("application.yml", "spring.datasource.url"))
        .contains("logServerErrorDetail=false");
  }

  private static String property(String file, String name) throws IOException {
    for (PropertySource<?> source :
        new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
      Object value = source.getProperty(name);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }
}

package in.agreementmitra.documents.template;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Resolves every {@code (state, type)} against the production {@code sets/rental} layers instead of
 * the reference fixture, for a plain-{@code test}-profile context that needs the shipped template's
 * defaults (e.g. the rent escalation the deed fills in) without the sandbox catalog. Import it only
 * into a class that already owns its own context, or it forks a new one.
 */
@TestConfiguration
public class ProductionRentalLayersTestConfig {

  @Bean
  @Primary
  LayerSource productionRentalLayerSource() {
    return new ClasspathLayerSource("documents/template/sets/rental/");
  }
}

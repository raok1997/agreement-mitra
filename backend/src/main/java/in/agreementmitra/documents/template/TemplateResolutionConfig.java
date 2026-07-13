package in.agreementmitra.documents.template;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the (package-private) resolution + projection collaborators as beans. Form projection is
 * the templating stack's first consumer, so it is the first CR to put the resolver on the Spring
 * context (the definition + resolution CRs added no beans -- nothing consumed them). Lives in
 * {@code documents.template} so it can construct the package-private {@link ClasspathLayerSource} /
 * {@link TemplateResolver}; the {@link LayerSource} bean is the seam a test can override with a
 * fixture.
 */
@Configuration
class TemplateResolutionConfig {

  @Bean
  LayerSource layerSource() {
    return new ClasspathLayerSource();
  }

  @Bean
  TemplateResolver templateResolver(LayerSource layerSource) {
    return new TemplateResolver(layerSource);
  }

  @Bean
  FormProjector formProjector() {
    return new FormProjector();
  }

  /**
   * The compiler, constructed once with the Noto {@code @font-face} CSS loaded from the classpath
   * so every compiled document is self-contained and identical for both render tiers (parity,
   * design D-A). Font loading happens here at bean init, keeping {@link TemplateCompiler} itself
   * pure.
   */
  @Bean
  TemplateCompiler templateCompiler() {
    return new TemplateCompiler(DocumentFonts.faceCss());
  }

  /**
   * The system clock the {@link DocumentProjectionService} reads to resolve the SYSDATE fallback
   * for the execution date when a submitted {@code agreementDate} is blank (design D3). {@code
   * Clock.systemDefaultZone()} in the app; a test injects {@code Clock.fixed(...)} to pin the
   * header deterministically. The pure {@link TemplateCompiler} never reads it.
   */
  @Bean
  Clock documentClock() {
    return Clock.systemDefaultZone();
  }
}

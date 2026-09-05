package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Registry-backed resolution + seed integration test against real Postgres. Runs under {@code
 * test,sandbox} so the seed loader populates the catalog and the {@link RegistryLayerSource} is the
 * primary {@link LayerSource} the wired {@link TemplateResolver} uses. Asserts: the seed rows are
 * present + published; every published row's {@code layerSetRef} pointer resolves to a real
 * classpath layer set (pointer integrity); and a picked template resolves via the registry to a
 * content hash <b>identical</b> to a classpath resolution of the same layer set (only the lookup
 * source changed). {@code disabledWithoutDocker = true} makes it skip (not fail) without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles({"test", "sandbox"})
@Testcontainers(disabledWithoutDocker = true)
class TemplateCatalogRegistryIntegrationTest {

  @Autowired private TemplateCatalogRepository repository;
  @Autowired private TemplateResolver templateResolver; // primary LayerSource = RegistryLayerSource

  // A direct classpath resolver over the SAME layer set the seed rows point at, for the parity
  // comparison: only the lookup source (DB pointer vs classpath convention) differs, not the
  // content.
  private final TemplateResolver classpathResolver =
      new TemplateResolver(new ClasspathLayerSource(TemplateCatalogSeeder.REFERENCE_LAYER_SET_REF));

  // A direct classpath resolver over the commercial layer set, for the commercial parity check.
  private final TemplateResolver commercialClasspathResolver =
      new TemplateResolver(
          new ClasspathLayerSource(TemplateCatalogSeeder.SETS_ROOT + "commercial/"));

  @Test
  void seedPublishesTheReferenceTemplates() {
    List<TemplateCatalogEntry> published = repository.findPublished(null, null, "%");
    assertThat(published).isNotEmpty();
    assertThat(published)
        .allSatisfy(e -> assertThat(e.status()).isEqualTo(TemplateStatus.PUBLISHED));
    assertThat(published).extracting(TemplateCatalogEntry::state).contains("IN", "TG");
    // Seed version derives from the reference base definition's meta (no drift).
    assertThat(published).allSatisfy(e -> assertThat(e.version()).isEqualTo(1));
  }

  @Test
  void everyPublishedPointerResolvesToARealLayerSet() {
    for (TemplateCatalogEntry e : repository.findPublished(null, null, "%")) {
      String basePath = e.layerSetRef() + "base.yaml";
      assertThat(getClass().getClassLoader().getResource(basePath))
          .as("dangling layer_set_ref pointer: %s", basePath)
          .isNotNull();
    }
  }

  @Test
  void registryResolutionEqualsClasspathResolutionForTheSameLayerSet() {
    // (TG, residential): base + type + state-TG + state_type-TG-residential.
    assertSameHash("TG", "residential");
    // (IN, residential): base + type only.
    assertSameHash("IN", "residential");
  }

  @Test
  void discoveredCommercialTemplateResolvesViaTheRegistryToTheCommercialDocument() {
    // The seeder discovered the commercial layer-set folder, so (TG, commercial) resolves via the
    // registry to the commercial document -- correctly headed, with the Telangana overlay -- and to
    // a content hash identical to a direct classpath resolution of the same set.
    EffectiveTemplate viaRegistry = templateResolver.resolve(new Dimensions("TG", "commercial"));
    assertThat(viaRegistry.template().meta().document().title())
        .isEqualTo("Commercial Lease Agreement");
    assertThat(viaRegistry.template().sections().stream().map(Section::title))
        .contains("Statutory (Telangana)", "In Witness Whereof");

    EffectiveTemplate viaClasspath =
        commercialClasspathResolver.resolve(new Dimensions("TG", "commercial"));
    assertThat(viaRegistry.contentHash()).isEqualTo(viaClasspath.contentHash());
    assertThat(viaRegistry.provenance()).isEqualTo(viaClasspath.provenance());
  }

  private void assertSameHash(String state, String type) {
    EffectiveTemplate viaRegistry = templateResolver.resolve(new Dimensions(state, type));
    EffectiveTemplate viaClasspath = classpathResolver.resolve(new Dimensions(state, type));
    assertThat(viaRegistry.contentHash())
        .as("registry vs classpath content hash for (%s, %s)", state, type)
        .isEqualTo(viaClasspath.contentHash());
    assertThat(viaRegistry.provenance()).isEqualTo(viaClasspath.provenance());
  }
}

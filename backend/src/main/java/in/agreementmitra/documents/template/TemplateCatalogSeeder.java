package in.agreementmitra.documents.template;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the catalog with the existing reference template(s) as <b>published</b>, dummy,
 * system-authored rows so the browse screen is non-empty on a fresh database. An application seed
 * loader (not a Flyway data insert) so the seed metadata <b>derives from the reference
 * definition</b> -- version comes from the reference base's {@code meta} -- and cannot drift from
 * the layer set it points at (design Open Question: loader over data-insert).
 *
 * <p>Gated to {@code local}/{@code sandbox} (sandbox + dummy data only) and idempotent: it inserts
 * only when the catalog is empty, so a restart never duplicates rows. Each row's {@code
 * layerSetRef} points at the real reference layer set root; the bodies stay classpath resources.
 */
@Component
@Profile({"local", "sandbox"})
class TemplateCatalogSeeder implements ApplicationRunner {

  /** The production layer set root the seed rows point at (a classpath pointer, never a body). */
  static final String REFERENCE_LAYER_SET_REF = "documents/template/sets/rental/";

  private static final String REFERENCE_BASE = REFERENCE_LAYER_SET_REF + "base.yaml";

  private final TemplateCatalogRepository repository;
  private final TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();

  TemplateCatalogSeeder(TemplateCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (repository.count() > 0) {
      return; // Already seeded (idempotent).
    }
    // Version derives from the reference base definition so catalog metadata cannot drift from the
    // layer set it points at.
    int version = definitionLoader.loadResource(REFERENCE_BASE).meta().version();

    List<TemplateCatalogEntry> seed =
        List.of(
            TemplateCatalogEntry.create(
                "Residential Rental Agreement (National)",
                "India-standard residential rental agreement (Leave and Licence) with mandatory and"
                    + " optional sections and system-authored defaults.",
                "residential",
                "IN",
                TemplateCatalogEntry.DEFAULT_LANGUAGE,
                version,
                TemplateStatus.PUBLISHED,
                REFERENCE_LAYER_SET_REF),
            TemplateCatalogEntry.create(
                "Residential Rental Agreement (Telangana)",
                "Telangana residential rental agreement with a state-specific statutory overlay"
                    + " (Telangana tenancy law, stamping / registration, jurisdiction).",
                "residential",
                "TG",
                TemplateCatalogEntry.DEFAULT_LANGUAGE,
                version,
                TemplateStatus.PUBLISHED,
                REFERENCE_LAYER_SET_REF));

    repository.saveAll(seed);
  }
}

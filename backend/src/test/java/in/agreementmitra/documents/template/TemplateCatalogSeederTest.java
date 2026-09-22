package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The catalog seeder over a mocked repository, exercising the real classpath discovery (no Spring
 * context, no Docker). Asserts: (1) on an empty catalog it discovers a published, version-derived
 * row per {@code (state, type)} the layer-set folders expose -- including the residential (IN + TG)
 * and commercial (IN + TG) production sets -- with no duplicate dimension pair and each row
 * pointing at its discovered layer-set root; and (2) it is idempotent per {@code (state, type)} --
 * re-running with every discovered row already present inserts nothing, and a pre-existing
 * dimension pair is skipped.
 *
 * <p>The unit-test classpath also carries fixture sets under {@code sets/} that the real app
 * (main-only classpath) never sees, so this asserts the production rows are <b>present</b> rather
 * than an exact whole-catalog match.
 */
class TemplateCatalogSeederTest {

  private final TemplateCatalogRepository repository = mock(TemplateCatalogRepository.class);
  private final TemplateCatalogSeeder seeder = new TemplateCatalogSeeder(repository);

  private static TemplateCatalogEntry existing(String type, String state, String ref) {
    return TemplateCatalogEntry.create(
        type + "-" + state, "blurb", type, state, "en", 1, TemplateStatus.PUBLISHED, ref);
  }

  private static String key(TemplateCatalogEntry e) {
    return e.state() + "|" + e.type();
  }

  @Test
  void emptyCatalogDiscoversTheProductionRowsWithoutDuplicateDimensions() {
    when(repository.findAll()).thenReturn(List.of());

    seeder.run(null);

    ArgumentCaptor<List<TemplateCatalogEntry>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    List<TemplateCatalogEntry> saved = captor.getValue();

    List<String> keys = saved.stream().map(TemplateCatalogSeederTest::key).toList();
    assertThat(keys)
        .contains("IN|residential", "TG|residential", "IN|commercial", "TG|commercial")
        .doesNotHaveDuplicates();
    assertThat(saved).allSatisfy(e -> assertThat(e.status()).isEqualTo(TemplateStatus.PUBLISHED));
    // A catalog row's version is derived from its layer set's BASE meta.version, so a base bump
    // propagates here. rental/base.yaml is v2 (the "(Leave & Licence)" label removal);
    // commercial/base.yaml is still v1. Asserted per set so the derivation stays pinned and a
    // future bump fails loudly rather than drifting.
    assertThat(saved)
        .filteredOn(e -> e.type().equals("residential"))
        .allSatisfy(e -> assertThat(e.version()).isEqualTo(2));
    assertThat(saved)
        .filteredOn(e -> e.type().equals("commercial"))
        .allSatisfy(e -> assertThat(e.version()).isEqualTo(1));
    // The commercial rows are named from the commercial base header and point at its layer set.
    assertThat(saved)
        .filteredOn(e -> e.type().equals("commercial"))
        .hasSize(2)
        .allSatisfy(
            e -> assertThat(e.layerSetRef()).isEqualTo("documents/template/sets/commercial/"))
        .allSatisfy(e -> assertThat(e.name()).startsWith("Commercial Lease Agreement"));
  }

  @Test
  void reRunWithEveryDiscoveredRowAlreadyPresentInsertsNothing() {
    when(repository.findAll()).thenReturn(List.of());
    seeder.run(null);

    ArgumentCaptor<List<TemplateCatalogEntry>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    List<TemplateCatalogEntry> firstInsert = captor.getValue();

    // Second run sees exactly what the first inserted: nothing new to add.
    when(repository.findAll()).thenReturn(firstInsert);
    seeder.run(null);

    verify(repository, times(1)).saveAll(any()); // still only the first run inserted.
  }

  @Test
  void aPresentDimensionPairIsSkipped() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                existing("commercial", "IN", "documents/template/sets/commercial/"),
                existing("commercial", "TG", "documents/template/sets/commercial/")));

    seeder.run(null);

    ArgumentCaptor<List<TemplateCatalogEntry>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    List<String> keys = captor.getValue().stream().map(TemplateCatalogSeederTest::key).toList();

    assertThat(keys).doesNotContain("IN|commercial", "TG|commercial");
  }
}

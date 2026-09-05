package in.agreementmitra.documents.template;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the catalog by <b>discovering the layer sets on the classpath</b> under {@code
 * documents/template/sets/} -- so a template is registered by <b>dropping its layer-set folder</b>,
 * with no per-template code change. For each set it reads the {@code base.yaml} {@code meta} (the
 * base gives the national {@code (state, type)} row) and every {@code state-<XX>.patch.yaml}
 * overlay beside it (each gives a {@code (XX, type)} row); the row's {@code version}, {@code
 * status}, name, and description <b>derive from the base definition</b> and cannot drift from the
 * layer set they point at (design Open Question: loader over data-insert). Only sets whose base is
 * {@code published} are seeded.
 *
 * <p>Gated to {@code local}/{@code sandbox} (sandbox + dummy data only) and idempotent <b>per
 * {@code (state, type)} dimension pair</b>: it inserts only the discovered rows whose {@code
 * (state, type)} is not already present, so a restart never duplicates a row and a database seeded
 * before a new layer-set folder was added picks up the missing rows on the next start. Each row's
 * {@code layerSetRef} points at the discovered classpath layer-set root; the bodies stay classpath
 * resources.
 */
@Component
@Profile({"local", "sandbox"})
class TemplateCatalogSeeder implements ApplicationRunner {

  /** Classpath root under which each template layer-set folder lives (a pointer, never a body). */
  static final String SETS_ROOT = "documents/template/sets/";

  /**
   * The residential layer set root, kept as a named constant for the registry parity integration
   * test that resolves the same set directly from the classpath.
   */
  static final String REFERENCE_LAYER_SET_REF = SETS_ROOT + "rental/";

  /** Human-readable names for the state dimension; unmapped codes fall back to the raw code. */
  private static final Map<String, String> STATE_DISPLAY_NAMES =
      Map.of("IN", "National", "TG", "Telangana");

  private final TemplateCatalogRepository repository;
  private final TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
  private final PathMatchingResourcePatternResolver resourceResolver =
      new PathMatchingResourcePatternResolver(getClass().getClassLoader());

  TemplateCatalogSeeder(TemplateCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    List<TemplateCatalogEntry> discovered = discoverSeedRows();

    // Insert only rows whose (state, type) dimension pair is not already present, so a re-run adds
    // only the missing rows and never duplicates an existing one.
    Set<String> present =
        repository.findAll().stream()
            .map(entry -> dimensionKey(entry.state(), entry.type()))
            .collect(Collectors.toSet());
    List<TemplateCatalogEntry> toInsert =
        discovered.stream()
            .filter(entry -> !present.contains(dimensionKey(entry.state(), entry.type())))
            .toList();

    if (!toInsert.isEmpty()) {
      repository.saveAll(toInsert);
    }
  }

  /**
   * Discover one seed row per {@code (state, type)} the classpath layer sets expose: the base's
   * national dimensions plus each {@code state-<XX>} overlay beside it. Deterministic in the layer
   * set root so a re-run produces the same rows.
   */
  private List<TemplateCatalogEntry> discoverSeedRows() {
    // Deduplicate by classpath-relative root: the same set can surface under more than one
    // classpath entry (e.g. build/resources + src/resources) with distinct absolute URLs.
    Map<String, TemplateDefinition> basesByRoot = new LinkedHashMap<>();
    for (Resource base : resources(SETS_ROOT + "*/base.yaml")) {
      String root = classpathRootOf(base);
      basesByRoot.putIfAbsent(root, definitionLoader.loadResource(root + "base.yaml"));
    }

    // First-wins per (state, type): a catalog row keys on the dimension pair, so two sets that ever
    // claim the same (state, type) must not each produce a row (that would make resolution
    // ambiguous). In production one set owns each pair; this only guards against a stray collision.
    Map<String, TemplateCatalogEntry> rowsByDimension = new LinkedHashMap<>();
    for (Map.Entry<String, TemplateDefinition> set : basesByRoot.entrySet()) {
      String root = set.getKey();
      TemplateDefinition base = set.getValue();
      if (base.meta().status() != TemplateStatus.PUBLISHED) {
        continue; // Only published bases are catalog-visible.
      }
      String type = base.meta().dimensions().type();
      int version = base.meta().version();
      String title = catalogTitle(base, type);
      String description = catalogDescription(base);

      // National row from the base's own dimensions.
      addRow(
          rowsByDimension,
          title,
          description,
          type,
          base.meta().dimensions().state(),
          version,
          root);

      // One row per state overlay (state-<XX>.patch.yaml; state_type-... is excluded by the glob).
      for (Resource statePatch : resources(root + "state-*.patch.yaml")) {
        addRow(rowsByDimension, title, description, type, stateCodeOf(statePatch), version, root);
      }
    }
    return List.copyOf(rowsByDimension.values());
  }

  private static void addRow(
      Map<String, TemplateCatalogEntry> rowsByDimension,
      String title,
      String description,
      String type,
      String state,
      int version,
      String root) {
    rowsByDimension.putIfAbsent(
        dimensionKey(state, type), row(title, description, type, state, version, root));
  }

  private static TemplateCatalogEntry row(
      String title, String description, String type, String state, int version, String root) {
    return TemplateCatalogEntry.create(
        title + " (" + stateDisplayName(state) + ")",
        description,
        type,
        state,
        TemplateCatalogEntry.DEFAULT_LANGUAGE,
        version,
        TemplateStatus.PUBLISHED,
        root);
  }

  private Resource[] resources(String classpathPattern) {
    try {
      return resourceResolver.getResources("classpath*:" + classpathPattern);
    } catch (IOException e) {
      throw new IllegalStateException("failed to scan template layer sets: " + classpathPattern, e);
    }
  }

  /** The base's document title, or the raw type when a base declares no document header. */
  private static String catalogTitle(TemplateDefinition base, String type) {
    DocumentMeta document = base.meta().document();
    return document != null && document.title() != null ? document.title() : type;
  }

  private static String catalogDescription(TemplateDefinition base) {
    DocumentMeta document = base.meta().document();
    return document != null ? document.subtitle() : null;
  }

  private static String stateDisplayName(String state) {
    return STATE_DISPLAY_NAMES.getOrDefault(state, state);
  }

  /**
   * The classpath-relative layer-set root (ending in {@code /}) for a discovered {@code base.yaml},
   * e.g. {@code documents/template/sets/commercial/}. Derived from the resource URL so it is
   * identical for a filesystem (dev) or jar (packaged) classpath.
   */
  private static String classpathRootOf(Resource baseResource) {
    String url = urlString(baseResource);
    int start = url.indexOf(SETS_ROOT);
    if (start < 0) {
      throw new IllegalStateException("unexpected template resource location: " + url);
    }
    String fromSets = url.substring(start); // documents/template/sets/<name>/base.yaml
    return fromSets.substring(0, fromSets.length() - "base.yaml".length());
  }

  /** The state code from a {@code state-<XX>.patch.yaml} resource (e.g. {@code TG}). */
  private static String stateCodeOf(Resource statePatch) {
    String url = urlString(statePatch);
    String fileName = url.substring(url.lastIndexOf('/') + 1); // state-XX.patch.yaml
    return fileName.substring("state-".length(), fileName.length() - ".patch.yaml".length());
  }

  private static String urlString(Resource resource) {
    try {
      return resource.getURL().toString();
    } catch (IOException e) {
      throw new IllegalStateException("failed to read template resource location", e);
    }
  }

  private static String dimensionKey(String state, String type) {
    return state + "|" + type;
  }
}

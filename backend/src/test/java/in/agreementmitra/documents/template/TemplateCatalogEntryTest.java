package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Identity + persistable semantics of the catalog aggregate (pure object level, no Spring). */
class TemplateCatalogEntryTest {

  private static TemplateCatalogEntry sample() {
    return TemplateCatalogEntry.create(
        "Residential Rental Agreement",
        "A dummy blurb",
        "residential",
        "TG",
        TemplateCatalogEntry.DEFAULT_LANGUAGE,
        1,
        TemplateStatus.PUBLISHED,
        "documents/template/examples/layers/");
  }

  @Test
  void factoryAssignsStableIdAndTimestamp() {
    TemplateCatalogEntry e = sample();
    assertThat(e.getId()).isNotNull();
    assertThat(e.createdAt()).isNotNull();
    // Id is stable across reads (identity from birth).
    assertThat(e.getId()).isEqualTo(e.getId());
  }

  @Test
  void isNewFlipsAfterPersistOrLoadCallback() {
    TemplateCatalogEntry e = sample();
    assertThat(e.isNew()).isTrue();
    e.markNotNew(); // simulates @PostPersist/@PostLoad
    assertThat(e.isNew()).isFalse();
  }

  @Test
  void toStringIsIdOnlyAndLeaksNoMetadata() {
    TemplateCatalogEntry e = sample();
    String s = e.toString();
    assertThat(s).contains(e.getId().toString());
    assertThat(s).doesNotContain("Residential Rental Agreement").doesNotContain("dummy blurb");
  }

  @Test
  void equalsAndHashCodeAreIdBased() {
    TemplateCatalogEntry a = sample();
    TemplateCatalogEntry b = sample();
    assertThat(a).isEqualTo(a).isNotEqualTo(b); // different ids
    assertThat(a.hashCode()).isEqualTo(java.util.Objects.hashCode(a.getId()));
  }

  @Test
  void carriesMetadataAndAPointerNeverABody() {
    TemplateCatalogEntry e = sample();
    assertThat(e.type()).isEqualTo("residential");
    assertThat(e.state()).isEqualTo("TG");
    assertThat(e.language()).isEqualTo("en");
    assertThat(e.status()).isEqualTo(TemplateStatus.PUBLISHED);
    // The pointer is a resource location, not a body.
    assertThat(e.layerSetRef()).isEqualTo("documents/template/examples/layers/");
    assertThat(e.layerSetRef()).doesNotContain("clauses").doesNotContain("<html");
  }

  @Test
  void distinctInstancesHaveDistinctIds() {
    assertThat(sample().getId()).isNotEqualTo(sample().getId());
    assertThat(UUID.fromString(sample().getId().toString())).isNotNull();
  }
}

package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateSummary;
import org.junit.jupiter.api.Test;

/** The entity -> DTO contract: exactly the system-owned metadata, no body/pointer/secret. */
class TemplateCatalogMapperTest {

  private static TemplateCatalogEntry entry() {
    return TemplateCatalogEntry.create(
        "Residential Rental Agreement (Telangana)",
        "Telangana overlay blurb",
        "residential",
        "TG",
        "en",
        3,
        TemplateStatus.PUBLISHED,
        "documents/template/examples/layers/");
  }

  @Test
  void summaryCarriesMetadataAndNoPointer() {
    TemplateCatalogEntry e = entry();
    TemplateSummary s = TemplateCatalogMapper.toSummary(e);

    assertThat(s.id()).isEqualTo(e.getId().toString());
    assertThat(s.name()).isEqualTo("Residential Rental Agreement (Telangana)");
    assertThat(s.description()).isEqualTo("Telangana overlay blurb");
    assertThat(s.type()).isEqualTo("residential");
    assertThat(s.state()).isEqualTo("TG");
    assertThat(s.language()).isEqualTo("en");
    assertThat(s.version()).isEqualTo(3);
    // No pointer, status, or timestamp leaks into the DTO.
    assertThat(s.toString()).doesNotContain("examples/layers");
  }

  @Test
  void detailCarriesDimensionsAndVersion() {
    TemplateCatalogEntry e = entry();
    TemplateDetail d = TemplateCatalogMapper.toDetail(e);

    assertThat(d.id()).isEqualTo(e.getId().toString());
    assertThat(d.name()).isEqualTo("Residential Rental Agreement (Telangana)");
    assertThat(d.dimensions()).isEqualTo(new TemplateDetail.Dimensions("TG", "residential", "en"));
    assertThat(d.version()).isEqualTo(3);
    assertThat(d.toString()).doesNotContain("examples/layers");
  }
}

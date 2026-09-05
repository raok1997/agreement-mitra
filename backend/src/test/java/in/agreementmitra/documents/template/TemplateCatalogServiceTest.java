package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Service contract over a mocked repository (no Spring, no DB): the service delegates to the
 * published-scoped finders, maps to DTOs, and signals not-found uniformly for unknown/non-published
 * ids (no oracle). Real SQL filtering is covered by the integration test.
 */
class TemplateCatalogServiceTest {

  private final TemplateCatalogRepository repo = mock(TemplateCatalogRepository.class);
  private final TemplateCatalogService service = new TemplateCatalogService(repo);

  private static TemplateCatalogEntry published(String name, String state, String type) {
    return TemplateCatalogEntry.create(
        name,
        "blurb",
        type,
        state,
        "en",
        1,
        TemplateStatus.PUBLISHED,
        "documents/template/examples/layers/");
  }

  @Test
  void listDelegatesToPublishedFinderAndMaps() {
    TemplateCatalogEntry e = published("Residential (TG)", "TG", "residential");
    when(repo.findPublished("TG", "residential", "%res%")).thenReturn(List.of(e));

    List<TemplateSummary> out = service.list("TG", "residential", " res ");

    // q is trimmed and wrapped into a contains-LIKE pattern; state/type kept.
    verify(repo).findPublished(eq("TG"), eq("residential"), eq("%res%"));
    assertThat(out).singleElement().satisfies(s -> assertThat(s.state()).isEqualTo("TG"));
  }

  @Test
  void listNormalizesBlankFiltersToNullAndMatchAllPattern() {
    when(repo.findPublished(null, null, "%")).thenReturn(List.of());
    service.list("", "   ", null);
    // Blank state/type -> null (ignored); blank q -> "%" (match-all, avoids a null LIKE bind).
    verify(repo).findPublished(null, null, "%");
  }

  @Test
  void listEscapesLikeMetacharactersSoTheyMatchAsLiterals() {
    when(repo.findPublished(null, null, "%50\\% off\\_the\\_rent%")).thenReturn(List.of());

    // A user typing '%' / '_' searches for those characters literally, not as LIKE wildcards.
    service.list(null, null, "50% off_the_rent");

    // '%' -> '\%', '_' -> '\_' (and a literal backslash would double first); ESCAPE '\' in the
    // query.
    verify(repo).findPublished(null, null, "%50\\% off\\_the\\_rent%");
  }

  @Test
  void listEscapesLiteralBackslashBeforeOtherMetacharacters() {
    when(repo.findPublished(null, null, "%a\\\\b%")).thenReturn(List.of());

    // A literal backslash is doubled so it stays a literal under ESCAPE '\'.
    service.list(null, null, "a\\b");

    verify(repo).findPublished(null, null, "%a\\\\b%");
  }

  @Test
  void detailReturnsPublishedEntry() {
    TemplateCatalogEntry e = published("Residential (IN)", "IN", "residential");
    when(repo.findByIdAndStatus(e.getId(), TemplateStatus.PUBLISHED)).thenReturn(Optional.of(e));

    TemplateDetail d = service.detail(e.getId().toString());

    assertThat(d.dimensions()).isEqualTo(new TemplateDetail.Dimensions("IN", "residential", "en"));
  }

  @Test
  void detailForUnknownOrNonPublishedSignalsNotFoundIdentically() {
    UUID missing = UUID.randomUUID();
    when(repo.findByIdAndStatus(missing, TemplateStatus.PUBLISHED)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(missing.toString()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void detailForMalformedIdIsNotFoundNotAServerError() {
    assertThatThrownBy(() -> service.detail("not-a-uuid"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void publishedTemplateIdForDelegatesToDimensionAuthority() {
    TemplateCatalogEntry e = published("Residential (TG)", "TG", "residential");
    when(repo.findFirstByStatusAndStateAndTypeOrderByVersionDesc(
            TemplateStatus.PUBLISHED, "TG", "residential"))
        .thenReturn(Optional.of(e));

    assertThat(service.publishedTemplateIdFor("TG", "residential")).contains(e.getId().toString());
  }

  @Test
  void publishedTemplateIdForUnknownDimensionsIsEmpty() {
    when(repo.findFirstByStatusAndStateAndTypeOrderByVersionDesc(
            TemplateStatus.PUBLISHED, "KA", "commercial"))
        .thenReturn(Optional.empty());

    assertThat(service.publishedTemplateIdFor("KA", "commercial")).isEmpty();
  }
}

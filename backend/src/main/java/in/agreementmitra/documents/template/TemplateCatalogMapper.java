package in.agreementmitra.documents.template;

import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateSummary;

/**
 * Maps a {@link TemplateCatalogEntry} to the public catalog DTOs. Pure and side-effect-free (no
 * Spring, no I/O), so the entity-to-DTO contract is unit-testable in isolation.
 *
 * <p>The mapping deliberately drops the internal {@code layerSetRef} pointer, the {@code status},
 * and {@code createdAt}: a DTO carries only system-owned browse metadata -- no body, no pointer, no
 * signer PII, no secret.
 */
final class TemplateCatalogMapper {

  private TemplateCatalogMapper() {}

  static TemplateSummary toSummary(TemplateCatalogEntry e) {
    return new TemplateSummary(
        e.getId().toString(),
        e.name(),
        e.description(),
        e.type(),
        e.state(),
        e.language(),
        e.version());
  }

  static TemplateDetail toDetail(TemplateCatalogEntry e) {
    return new TemplateDetail(
        e.getId().toString(),
        e.name(),
        e.description(),
        new TemplateDetail.Dimensions(e.state(), e.type(), e.language()),
        e.version());
  }
}

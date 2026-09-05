package in.agreementmitra.documents.template;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.documents.api.TemplateSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of the public {@link TemplateCatalogApi} port. Reads published
 * catalog rows through the {@link TemplateCatalogRepository} (whose finders are published-scoped by
 * construction) and maps entities to DTOs <em>inside</em> the transaction, so no entity crosses the
 * module boundary. Kept in {@code documents.template} (same package as the entity/records) so no
 * record visibility widens; Spring wires it as the {@code TemplateCatalogApi} bean the controller
 * consumes.
 */
@Service
class TemplateCatalogService implements TemplateCatalogApi {

  private final TemplateCatalogRepository repository;

  TemplateCatalogService(TemplateCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<TemplateSummary> list(String state, String type, String q) {
    return repository.findPublished(blankToNull(state), blankToNull(type), likePattern(q)).stream()
        .map(TemplateCatalogMapper::toSummary)
        .toList();
  }

  /**
   * A LIKE pattern that matches everything for a blank query, else a contains-match on the trim.
   * The user's text is treated as a <b>literal</b>: LIKE metacharacters ({@code \}, {@code %},
   * {@code _}) in the input are escaped so they match themselves, not as wildcards -- the query
   * uses {@code ESCAPE '\'} (see {@link TemplateCatalogRepository#findPublished}). Escape order
   * matters: {@code \} first, then {@code %} and {@code _}.
   */
  private static String likePattern(String q) {
    String trimmed = blankToNull(q);
    if (trimmed == null) {
      return "%";
    }
    String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  @Override
  @Transactional(readOnly = true)
  public TemplateDetail detail(String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      // A malformed id is not a published entry -- same 404 as unknown/non-published (no oracle).
      throw new ResourceNotFoundException("no published template for the requested id");
    }
    return repository
        .findByIdAndStatus(uuid, TemplateStatus.PUBLISHED)
        .map(TemplateCatalogMapper::toDetail)
        .orElseThrow(
            () -> new ResourceNotFoundException("no published template for the requested id"));
  }

  /**
   * The non-throwing lookup. Same published-only rule as {@link #detail(String)} and the same
   * indistinguishable treatment of unknown vs non-published (empty either way) -- it just reports
   * "no entry" as a value, so a caller inside a transaction is not forced to survive an exception
   * that has already marked that transaction rollback-only.
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<TemplateDetail> find(String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return Optional.empty(); // a malformed id is not a published entry
    }
    return repository
        .findByIdAndStatus(uuid, TemplateStatus.PUBLISHED)
        .map(TemplateCatalogMapper::toDetail);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> publishedTemplateIdFor(String state, String type) {
    return repository
        .findFirstByStatusAndStateAndTypeOrderByVersionDesc(TemplateStatus.PUBLISHED, state, type)
        .map(e -> e.getId().toString());
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }
}

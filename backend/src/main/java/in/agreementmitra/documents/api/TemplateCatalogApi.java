package in.agreementmitra.documents.api;

import java.util.List;
import java.util.Optional;

/**
 * Public port of the {@code documents} module for the <b>template catalog</b>: browse published
 * templates and fetch one by id. The implementation is package-private in {@code
 * in.agreementmitra.documents.template}; callers depend only on this port and the catalog DTOs.
 *
 * <p>Only <b>published</b> entries are ever exposed -- draft and deprecated entries are neither
 * listed nor fetchable, and unknown vs non-published ids are indistinguishable (both 404, no
 * existence oracle).
 */
public interface TemplateCatalogApi {

  /**
   * List published catalog entries, filtered by the optional {@code state} / {@code type}
   * dimensions and an optional free-text {@code q} over name/description. A {@code null}/blank
   * filter is ignored. Returns a possibly-empty list; never a draft or deprecated entry.
   */
  List<TemplateSummary> list(String state, String type, String q);

  /**
   * Fetch one published catalog entry by id.
   *
   * @throws in.agreementmitra.ResourceNotFoundException if the id is unknown <b>or</b> the entry is
   *     not published. The root {@code GlobalExceptionHandler} maps this to a 404 RFC 9457 {@code
   *     ProblemDetail} whose detail is a fixed constant and never echoes the requested id -- so a
   *     draft/deprecated entry's existence cannot be probed.
   */
  TemplateDetail detail(String id);

  /**
   * Fetch one published catalog entry by id, or empty when there is none.
   *
   * <p>The non-throwing sibling of {@link #detail(String)}, for callers that treat an unresolvable
   * template as <b>missing data rather than a failure</b> - the staff stamp queue, where an
   * agreement pinned to a since-deprecated template is still outstanding work and must stay on the
   * queue with less shown, not disappear from it.
   *
   * <p>It exists because {@code detail}'s exception cannot serve that case: this method runs inside
   * the caller's transaction, so a thrown exception marks the shared transaction rollback-only and
   * dooms the caller's commit <em>even if the caller catches it</em>. "Catch and carry on" is not
   * available across a transactional boundary; not throwing is.
   *
   * <p>Discloses no more than {@code detail}: same published-only rule, and empty is returned
   * identically for an unknown id and a non-published one, so this is not an existence oracle
   * either.
   */
  Optional<TemplateDetail> find(String id);

  /**
   * The id of the published catalog template for {@code (state, type)}, if one exists. This is the
   * catalog's <b>dimension-validation authority</b>: it is the intended integration point for the
   * form/document projections to reject an unknown {@code (state, type)} (closing the D7 gap where
   * a single national base made any dimensions resolve). Returns empty for dimensions no published
   * template covers.
   */
  Optional<String> publishedTemplateIdFor(String state, String type);
}

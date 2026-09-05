package in.agreementmitra.documents.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link TemplateCatalogEntry}. Package-private -- Modulith-internal.
 *
 * <p>Every finder is <b>published-scoped by construction</b>: a non-published row cannot escape a
 * query path, so a draft/deprecated entry is never listed, fetchable, or selectable (design D4, the
 * no-oracle discipline). The free-text search matches over name/description case-insensitively; a
 * blank {@code q} matches everything (still published-only). Filtering happens in the query so the
 * database does the work; the service maps entities to DTOs inside the transaction.
 */
interface TemplateCatalogRepository extends JpaRepository<TemplateCatalogEntry, UUID> {

  /**
   * Published entries filtered by optional {@code state} / {@code type} dimensions and a free-text
   * {@code pattern} over name/description. A {@code null} state/type filter is ignored (matches
   * all); {@code pattern} is always a non-null SQL LIKE pattern ({@code %} matches everything) --
   * the service passes {@code %} for a blank query, which keeps Postgres from having to infer the
   * type of a null {@code lower(...)} argument. The pattern is matched with an explicit {@code
   * ESCAPE '\'} so a {@code %} / {@code _} the caller escaped is treated as a literal, not a
   * wildcard (see {@code TemplateCatalogService.likePattern}). Ordering is stable by name then
   * version (a numeric column, so genuine numeric order) for a deterministic browse list.
   */
  @Query(
      """
      SELECT t FROM TemplateCatalogEntry t
       WHERE t.status = in.agreementmitra.documents.template.TemplateStatus.PUBLISHED
         AND (:state IS NULL OR t.state = :state)
         AND (:type IS NULL OR t.type = :type)
         AND (LOWER(t.name) LIKE LOWER(:pattern) ESCAPE '\\'
              OR (t.description IS NOT NULL AND LOWER(t.description) LIKE LOWER(:pattern) ESCAPE '\\'))
       ORDER BY t.name ASC, t.version ASC
      """)
  List<TemplateCatalogEntry> findPublished(
      @Param("state") String state, @Param("type") String type, @Param("pattern") String pattern);

  /**
   * A single entry by id, only if it is published; empty for unknown or non-published (no oracle).
   */
  Optional<TemplateCatalogEntry> findByIdAndStatus(UUID id, TemplateStatus status);

  /**
   * The published entry (if any) whose dimensions are exactly {@code (state, type)} -- the
   * registry-backed {@link RegistryLayerSource} and the dimension-validation authority. {@code
   * findFirst} + {@code OrderByVersionDesc} over the numeric {@code version} column picks the
   * genuinely latest published version when two ever coexist for a {@code (state, type)} (an int
   * column, so {@code DESC} is numeric -- {@code 10} outranks {@code 9}). Today dimensions are
   * unique per seed, so this is a single row; the ordering is the latent-correctness guard.
   */
  Optional<TemplateCatalogEntry> findFirstByStatusAndStateAndTypeOrderByVersionDesc(
      TemplateStatus status, String state, String type);
}

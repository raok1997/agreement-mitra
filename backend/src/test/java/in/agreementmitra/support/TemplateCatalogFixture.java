package in.agreementmitra.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds a published catalog row so a test can create an agreement in an <b>eligible
 * jurisdiction</b>.
 *
 * <p>Needed because two things are true at once: {@code TemplateCatalogSeeder} is
 * {@code @Profile({"local","sandbox"})} while tests run the {@code test} profile, so the catalog is
 * empty here; and since the jurisdiction-checkout-gating change, an agreement with no pinned
 * template has no duty jurisdiction and is refused at finalise, checkout, e-stamp intake and eSign
 * initiation. So a fixture that used to create a bare agreement now has to pin one - and simply
 * sending {@code "state":"TG"} would 404, because {@code AgreementService.resolveSelectedTemplate}
 * throws when {@code publishedTemplateIdFor} finds nothing.
 *
 * <p><b>Additive, never a {@code DELETE FROM template}.</b> The schema is shared across the suite,
 * and deleting rows other agreements are pinned to leaves those agreements with a dangling template
 * id - a fixture bug that reads exactly like a product bug. A high version wins {@code
 * publishedTemplateIdFor}'s order-by, so this row is the one an agreement created afterwards pins
 * to regardless of what else the suite has seeded for the same dimensions.
 */
public final class TemplateCatalogFixture {

  /** The eligible jurisdiction the suite standardises on; matches the shipped default allowlist. */
  public static final String ELIGIBLE_STATE = "TG";

  /**
   * The national dimension: present in the catalog, deliberately NOT eligible for paid fulfilment.
   * Tests asserting the gate's refusal pin to this.
   */
  public static final String NATIONAL_STATE = "IN";

  public static final String TYPE = "residential";

  private static final String LAYER_SET_REF = "documents/template/examples/layers/";

  /**
   * Deliberately BELOW the version a test seeds when it pins to a template of its own.
   *
   * <p>Rows leak across the suite (the schema is shared and these inserts are additive), and {@code
   * publishedTemplateIdFor} orders by version descending. A fixture row at the same high version as
   * a test's own row would therefore outrank it and that test would resolve to this generic
   * template instead of the one it seeded - which surfaces as a name assertion failing for reasons
   * that have nothing to do with the test. Sitting below leaves any explicitly seeded row winning,
   * while still beating the ordinary low-version rows.
   */
  private static final int VERSION = 900;

  private TemplateCatalogFixture() {}

  /** Seed a published row in the eligible jurisdiction, returning its id. */
  public static UUID seedEligible(JdbcTemplate jdbc) {
    return seed(jdbc, ELIGIBLE_STATE);
  }

  /** Seed a published row in the national (ineligible) dimension, returning its id. */
  public static UUID seedNational(JdbcTemplate jdbc) {
    return seed(jdbc, NATIONAL_STATE);
  }

  /** Seed a published row for one state, returning its id. */
  public static UUID seed(JdbcTemplate jdbc, String state) {
    UUID templateId = UUID.randomUUID();
    String name = "Residential Rental (" + state + ")";
    jdbc.update(
        "INSERT INTO template (id, name, description, type, state, language, version, status,"
            + " layer_set_ref, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        templateId,
        name,
        name + " blurb",
        TYPE,
        state,
        "en",
        VERSION,
        "PUBLISHED",
        LAYER_SET_REF,
        Timestamp.from(Instant.now()));
    return templateId;
  }
}

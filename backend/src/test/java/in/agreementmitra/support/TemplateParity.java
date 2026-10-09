package in.agreementmitra.support;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The template PARITY CONTRACT, held once for every guard that enforces it (rental, commercial and
 * Karnataka layer-set tests on the {@code documents} side; {@code
 * AggregateBackedRequiredFieldsGuardIntegrationTest} on the {@code signing} side).
 *
 * <p>A field may be {@code required} with no default only if it is aggregate-backed (the mapper
 * supplies it at generate) or <b>user-answered</b>: named in {@link #USER_ANSWERED} and projected
 * as a {@code required} field in a mandatory ({@code optional=false}) capture section, so the form
 * always asks it and generate refuses until it is answered. The allowlist keeps each new
 * user-answered field a deliberate edit: placement alone would let a stray {@code required: true}
 * in any mandatory section silently break every in-flight draft.
 *
 * <p>Uses public {@code documents.api} types only, so both modules' tests can call it.
 */
public final class TemplateParity {

  /**
   * The keys {@code AgreementDocumentMapper.toTemplateData} emits. A copy, not the authority: the
   * signing-side guard asserts it equals the live mapper's key set. It MUST NOT gain {@code
   * subletting} -- that would make the user-answered branch dead.
   */
  public static final Set<String> AGGREGATE_KEYS =
      Set.of(
          "ownerName",
          "ownerFatherName",
          "ownerAddress",
          "tenantName",
          "tenantFatherName",
          "tenantAddress",
          "propertyAddress",
          "monthlyRent",
          "securityDeposit",
          "durationMonths",
          "startDate",
          "endDate");

  /** Required, undefaulted, non-aggregate fields the capture form must ask. */
  public static final Set<String> USER_ANSWERED = Set.of("subletting");

  /** One valid answer per {@link #USER_ANSWERED} key. */
  public static final Map<String, Object> USER_ANSWERS = Map.of("subletting", "with_owner_consent");

  /** A parity breach: the offending field key and why. */
  public record Violation(String key, String reason) {}

  private TemplateParity() {}

  /**
   * A copy of {@code data} with {@link #USER_ANSWERS} merged in. A key the caller already set wins,
   * so each test keeps its own deliberately-seeded data.
   */
  public static Map<String, Object> withUserAnswers(Map<String, Object> data) {
    Map<String, Object> merged = new LinkedHashMap<>(data);
    USER_ANSWERS.forEach(merged::putIfAbsent);
    return merged;
  }

  /**
   * The parity violations of one projected schema. {@code requiredUndefaultedKeys} is computed by
   * the caller: fields that are required, carry no default, and are neither derived nor
   * system-sourced. Each such key outside {@code aggregateKeys} must be allowlisted and projected
   * only as a required field in mandatory sections.
   */
  public static List<Violation> violations(
      FormSchema schema, Set<String> requiredUndefaultedKeys, Set<String> aggregateKeys) {
    List<Violation> found = new ArrayList<>();
    for (String key : requiredUndefaultedKeys) {
      if (aggregateKeys.contains(key)) {
        continue;
      }
      if (!USER_ANSWERED.contains(key)) {
        found.add(new Violation(key, "not aggregate-backed, defaulted or user-answered"));
      }
      List<Boolean> placements = requiredInMandatoryPerPlacement(schema, key);
      if (placements.isEmpty()) {
        found.add(new Violation(key, "in no capture section"));
      } else if (placements.contains(false)) {
        found.add(new Violation(key, "not a required field in a mandatory capture section"));
      }
    }
    return found;
  }

  /** One entry per section that projects {@code key}: is it required in a mandatory section? */
  private static List<Boolean> requiredInMandatoryPerPlacement(FormSchema schema, String key) {
    List<Boolean> placements = new ArrayList<>();
    for (FormSection section : schema.sections()) {
      for (FormField field : section.fields()) {
        if (field.key().equals(key)) {
          placements.add(!section.optional() && field.required());
        }
      }
    }
    return placements;
  }
}

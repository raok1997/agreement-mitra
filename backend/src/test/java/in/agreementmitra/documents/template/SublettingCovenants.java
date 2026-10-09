package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.support.TemplateParity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared assertions for the production layer-set tests: the parity contract (delegated to {@link
 * TemplateParity}) and the sub-letting choice, whose three covenants must render one at a time.
 *
 * <p>The covenant checks iterate the field's DECLARED options rather than a literal list: a {@code
 * showWhen} whose literal matches no option silently drops its clause, which would leave a deed
 * silent on sub-letting -- the Transfer of Property Act s.108(j) outcome the choice exists to
 * prevent. A new option without a covenant fails here.
 */
final class SublettingCovenants {

  static final List<String> CLAUSE_IDS =
      List.of(
          "sublettingWithConsentClause", "sublettingProhibitedClause", "sublettingPermittedClause");

  private SublettingCovenants() {}

  /** The parity violations of a resolved template, computed the way every documents guard does. */
  static List<TemplateParity.Violation> parityViolations(EffectiveTemplate eff) {
    FormSchema schema = new FormProjector().project(eff);
    return TemplateParity.violations(
        schema, requiredUndefaultedKeys(eff), TemplateParity.AGGREGATE_KEYS);
  }

  /** Required, no default, neither derived nor system-sourced. */
  static Set<String> requiredUndefaultedKeys(EffectiveTemplate eff) {
    return eff.template().fields().stream()
        .filter(f -> f.required() && f.defaultValue() == null)
        .filter(f -> !f.derived() && !f.systemSourced())
        .map(Field::key)
        .collect(Collectors.toSet());
  }

  /**
   * Compiles {@code eff} once per declared {@code subletting} option and asserts exactly one
   * covenant renders, worded for {@code party} (Tenant / Lessee) and {@code counterparty} (Owner /
   * Lessor), with the humanised option in the terms table.
   */
  static void assertEachOptionRendersItsOwnCovenant(
      EffectiveTemplate eff, Map<String, Object> data, String party, String counterparty) {
    Field subletting =
        eff.template().fields().stream()
            .filter(f -> f.key().equals("subletting"))
            .findFirst()
            .orElseThrow();
    Map<String, String> covenants = covenants(party, counterparty);
    assertThat(subletting.options()).isNotEmpty();
    for (String option : subletting.options()) {
      assertThat(covenants).as("a covenant is written for option '%s'", option).containsKey(option);
      Map<String, Object> answered = new LinkedHashMap<>(data);
      answered.put("subletting", option);
      String html =
          new TemplateCompiler()
              .compile(
                  eff,
                  SubmittedDataValidator.validateAndCoerce(eff, answered, ProjectionMode.GENERATE));

      assertThat(html)
          .as("option '%s' renders its covenant", option)
          .contains(covenants.get(option));
      assertThat(covenantCount(html)).as("exactly one covenant for '%s'", option).isEqualTo(1);
      assertThat(html)
          .contains("<td class=\"label\">Sub-letting</td><td>" + OptionLabels.humanize(option));
    }
  }

  /**
   * {@code subletting} is a required capture field in {@code Term}, with no default and exactly the
   * three options, placed immediately after {@code previousKey}.
   */
  static void assertRequiredTermFieldWithNoDefault(EffectiveTemplate eff, String previousKey) {
    FormField subletting =
        new FormProjector()
            .project(eff).sections().stream()
                .filter(s -> s.title().equals("Term"))
                .flatMap(s -> s.fields().stream())
                .filter(f -> f.key().equals("subletting"))
                .findFirst()
                .orElseThrow();
    assertThat(subletting.required()).isTrue();
    assertThat(subletting.defaultValue()).isNull();
    assertThat(subletting.options())
        .extracting(FormField.Option::value)
        .containsExactly("with_owner_consent", "not_allowed", "allowed");
    List<String> term =
        eff.template().sections().stream()
            .filter(s -> s.title().equals("Term"))
            .findFirst()
            .orElseThrow()
            .entries();
    assertThat(term.indexOf("subletting")).isEqualTo(term.indexOf(previousKey) + 1);
  }

  /** How many sub-letting covenants a compiled document carries. */
  static int covenantCount(String html) {
    return html.split(" sublet", -1).length - 1;
  }

  /** Every witnesseth list names the three covenant ids and not the retired fixed one. */
  static void assertTheWitnessethListsAllThreeCovenants(EffectiveTemplate eff) {
    Section witnesseth =
        eff.template().sections().stream()
            .filter(s -> s.title().equals("Now This Agreement Witnesseth"))
            .findFirst()
            .orElseThrow();
    assertThat(witnesseth.entries())
        .containsSubsequence(
            "careOfPremisesClause",
            "sublettingWithConsentClause",
            "sublettingProhibitedClause",
            "sublettingPermittedClause",
            "inspectionClause")
        .doesNotContain("noSublettingClause");
  }

  private static Map<String, String> covenants(String party, String counterparty) {
    return Map.of(
        "with_owner_consent",
        "The "
            + party
            + " shall not sublet, assign, or part with possession of the Premises, in whole or in"
            + " part, without the "
            + counterparty
            + "&#39;s prior written consent.",
        "not_allowed",
        "The "
            + party
            + " shall not sublet, assign, or part with possession of the Premises, in whole or in"
            + " part, under any circumstances.",
        "allowed",
        "The "
            + party
            + " may sublet the Premises, in whole or in part, after giving the "
            + counterparty
            + " prior written notice of the sub-tenant&#39;s name, and shall remain liable to the "
            + counterparty
            + " for the rent and every obligation under this Agreement. The "
            + party
            + " shall not assign this Agreement without the "
            + counterparty
            + "&#39;s prior written consent.");
  }
}

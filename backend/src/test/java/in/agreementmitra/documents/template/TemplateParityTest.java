package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.support.TemplateParity;
import in.agreementmitra.support.TemplateParity.Violation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The parity rule itself, over the {@code testsets/useranswered} fixture: a required, undefaulted,
 * non-aggregate field passes only when it is allowlisted AND projected as a required field in a
 * mandatory capture section. Lives in this package because resolving and projecting a fixture set
 * needs the package-private resolver and projector.
 */
class TemplateParityTest {

  private static final EffectiveTemplate FIXTURE =
      new TemplateResolver(new ClasspathLayerSource("documents/template/testsets/useranswered/"))
          .resolve(new Dimensions("IN", "residential"));

  @Test
  void namesEachMisplacedOrUnlistedRequiredFieldAndAcceptsTheAllowlistedOne() {
    FormSchema schema = new FormProjector().project(FIXTURE);

    List<Violation> violations =
        TemplateParity.violations(
            schema,
            SublettingCovenants.requiredUndefaultedKeys(FIXTURE),
            TemplateParity.AGGREGATE_KEYS);

    assertThat(violations)
        .containsExactlyInAnyOrder(
            new Violation("carpetAreaSqft", "not aggregate-backed, defaulted or user-answered"),
            new Violation("petPolicy", "not aggregate-backed, defaulted or user-answered"),
            new Violation("petPolicy", "not a required field in a mandatory capture section"),
            new Violation("orphanAnswer", "not aggregate-backed, defaulted or user-answered"),
            new Violation("orphanAnswer", "in no capture section"));
    assertThat(violations).extracting(Violation::key).doesNotContain("subletting", "ownerName");
  }

  @Test
  void anAggregateKeyIsNeverAViolationWhereverItSits() {
    FormSchema schema = new FormProjector().project(FIXTURE);

    assertThat(
            TemplateParity.violations(schema, Set.of("ownerName"), TemplateParity.AGGREGATE_KEYS))
        .isEmpty();
  }

  @Test
  void userAnswersMergeWithoutOverridingSeededData() {
    assertThat(TemplateParity.withUserAnswers(Map.of("subletting", "allowed", "a", 1)))
        .containsEntry("subletting", "allowed")
        .containsEntry("a", 1);
    assertThat(TemplateParity.withUserAnswers(Map.of())).isEqualTo(TemplateParity.USER_ANSWERS);
  }
}

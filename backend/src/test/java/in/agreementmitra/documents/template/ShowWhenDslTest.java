package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code showWhen} validator + evaluator semantics (7.6): the validator rejects a reference to an
 * undeclared field; the evaluator returns correct booleans over a value map, is side-effect-free,
 * and enforces operand-type rules. Pure unit tests, no I/O.
 */
class ShowWhenDslTest {

  // --- validator (data-independent, part of resolution) ----------------------

  @Test
  void validationAcceptsAConditionOverDeclaredFields() {
    // Does not throw.
    ShowWhenValidator.validate(templateWithCondition("furnished == true && rent > 0"));
  }

  @Test
  void validationRejectsAnUndeclaredFieldReference() {
    assertThatThrownBy(
            () -> ShowWhenValidator.validate(templateWithCondition("mysteryField == true")))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("c")
        .hasMessageContaining("mysteryField");
  }

  @Test
  void validationRejectsAConditionThatDoesNotParse() {
    assertThatThrownBy(() -> ShowWhenValidator.validate(templateWithCondition("rent = 0")))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("c");
  }

  // --- evaluator (pure; delivered but unwired) -------------------------------

  @Test
  void evaluatesBooleanAndComparisonCases() {
    Map<String, Object> values = Map.of("furnished", true, "rent", 1000L);

    assertThat(eval("furnished == true", values)).isTrue();
    assertThat(eval("furnished", values)).isTrue();
    assertThat(eval("rent > 500", values)).isTrue();
    assertThat(eval("rent < 500", values)).isFalse();
    assertThat(eval("furnished && rent > 500", values)).isTrue();
    assertThat(eval("!furnished || rent > 500", values)).isTrue();
    assertThat(eval("rent >= 1000 && rent <= 1000", values)).isTrue();
  }

  @Test
  void evaluatesStringEqualityAndDateOrdering() {
    assertThat(eval("purpose == \"residential\"", Map.of("purpose", "residential"))).isTrue();
    assertThat(eval("purpose != \"commercial\"", Map.of("purpose", "residential"))).isTrue();
    assertThat(
            eval("startDate < endDate", Map.of("startDate", "2025-01-01", "endDate", "2025-06-01")))
        .isTrue();
  }

  @Test
  void theEvaluatorIsSideEffectFree() {
    Map<String, Object> values = new HashMap<>();
    values.put("furnished", true);
    values.put("rent", 1000L);
    ShowWhenExpr expr = ShowWhenParser.parse("furnished && rent > 500");

    boolean first = ShowWhenEvaluator.evaluate(expr, values);
    boolean second = ShowWhenEvaluator.evaluate(expr, values);

    assertThat(first).isEqualTo(second).isTrue();
    assertThat(values).hasSize(2).containsEntry("furnished", true).containsEntry("rent", 1000L);
  }

  @Test
  void orderingComparisonAcrossIncompatibleTypesIsAnOperandTypeError() {
    assertThatThrownBy(() -> eval("rent > true", Map.of("rent", 1000L)))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void equalityAcrossMismatchedTypesIsAnOperandTypeError() {
    assertThatThrownBy(() -> eval("rent == true", Map.of("rent", 1000L)))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void aNonBooleanFieldInABooleanContextIsAnOperandTypeError() {
    assertThatThrownBy(() -> eval("furnished", Map.of("furnished", "yes")))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void aBareNumberIsNotABooleanExpression() {
    assertThatThrownBy(() -> eval("5", Map.of())).isInstanceOf(ShowWhenException.class);
  }

  @Test
  void aMissingFieldValueIsAnEvaluationError() {
    assertThatThrownBy(() -> eval("rent > 0", Map.of())).isInstanceOf(ShowWhenException.class);
  }

  // --- helpers ---------------------------------------------------------------

  private static boolean eval(String source, Map<String, Object> values) {
    return ShowWhenEvaluator.evaluate(ShowWhenParser.parse(source), values);
  }

  private static TemplateDefinition templateWithCondition(String showWhen) {
    String yaml =
        """
        meta: { id: t, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: furnished, label: Furnished, type: bool, required: false, default: false }
          - { key: rent, label: Rent, type: money, required: true }
        clauses:
          - { id: c, text: "A note.", showWhen: "%s" }
        sections:
          - { title: S, entries: [ furnished, rent, c ] }
        """
            .formatted(showWhen);
    return new TemplateDefinitionLoader().load(yaml);
  }
}
